package org.metadatacenter.artifacts.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration test: spawns the shaded server jar as a real subprocess, speaks
 * real JSON-RPC over its real stdin/stdout, and validates the rendered template with
 * {@link CedarValidator} (the same validator the artifact library's own renderer tests
 * use).
 *
 * <p>Unlike the in-process unit tests in {@code CreateTemplateToolTest}, this exercise
 * exposes failures in the shading, classpath layering, stdio transport, and tool
 * registration that an in-process handler call would silently bypass. It is the
 * regression net for the Jackson 2.x / Jackson 3.x classpath skew we hit during
 * scaffold, among other shading-only failure modes.
 *
 * <p>Runs in the {@code verify} phase via maven-failsafe-plugin. Name pattern
 * {@code *IT.java} ensures surefire skips it; failsafe picks it up.
 */
final class EndToEndStdioIT
{
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectMapper jackson = new ObjectMapper();
  private final ModelValidator cedarValidator = new CedarValidator();

  @Test void server_responds_to_initialize_lists_tools_and_validates_created_template() throws Exception
  {
    Path jar = locateShadedJar();

    ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar.toString())
        .redirectErrorStream(false);
    Process server = pb.start();

    // Drain stderr to a buffer so the JVM doesn't block on a full pipe and so we can
    // attach the SLF4J / startup output to a failure report if anything goes wrong.
    StringBuilder stderr = new StringBuilder();
    Thread stderrPump = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) synchronized (stderr) { stderr.append(line).append('\n'); }
      } catch (IOException ignored) {}
    }, "stderr-pump");
    stderrPump.setDaemon(true);
    stderrPump.start();

    BufferedReader stdout = new BufferedReader(
        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    try (Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      // 1. initialize ----------------------------------------------------------------
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
          + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"0\"}}}");
      JsonNode initResponse = readResponse(stdout, stderr);
      assertEquals(1, initResponse.path("id").asInt(), "initialize response should match id=1");
      assertEquals("cedar-artifact-mcp",
          initResponse.path("result").path("serverInfo").path("name").asText(),
          "server should identify itself as cedar-artifact-mcp");

      // 2. initialized notification (no response expected) ---------------------------
      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

      // 3. tools/list ---------------------------------------------------------------
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
      JsonNode listResponse = readResponse(stdout, stderr);
      assertEquals(2, listResponse.path("id").asInt());
      List<String> toolNames = new ArrayList<>();
      listResponse.path("result").path("tools").forEach(t -> toolNames.add(t.path("name").asText()));
      assertTrue(toolNames.contains("ping"), "ping tool should be listed; got " + toolNames);
      assertTrue(toolNames.contains("create_template"),
          "create_template tool should be listed; got " + toolNames);
      assertTrue(toolNames.contains("template_from_yaml"),
          "template_from_yaml tool should be listed; got " + toolNames);

      // 4. tools/call create_template -----------------------------------------------
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"create_template\",\"arguments\":"
          + "{\"name\":\"Patient demographics\","
          + "\"description\":\"End-to-end test template\","
          + "\"version\":\"0.1.0\"}}}");
      JsonNode callResponse = readResponse(stdout, stderr);
      assertEquals(3, callResponse.path("id").asInt());

      JsonNode result = callResponse.path("result");
      assertFalse(result.path("isError").asBoolean(true),
          "tool should not report error; full response: " + callResponse);

      // The first content block is the rendered template JSON as text.
      JsonNode content = result.path("content");
      assertTrue(content.isArray() && content.size() >= 1,
          "result.content must be a non-empty array");
      String renderedText = content.get(0).path("text").asText();
      assertNotNull(renderedText, "rendered template text must be present");
      assertFalse(renderedText.isBlank(), "rendered template text must not be blank");

      JsonNode parsed = jackson.readTree(renderedText);
      assertTrue(parsed.isObject(), "rendered template must be a JSON object");

      assertEquals("Patient demographics", parsed.path("schema:name").asText());
      assertEquals("0.1.0", parsed.path("pav:version").asText());

      // Re-validate end-to-end: the JSON the wire returned must satisfy CedarValidator.
      ValidationReport report = cedarValidator.validateTemplate(parsed);
      if (!"true".equals(report.getValidationStatus())) {
        StringBuilder msg = new StringBuilder(
            "CedarValidator rejected the template returned over stdio:\n");
        for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
        fail(msg.toString());
      }
    } finally {
      shutdown(server, stderr);
    }
  }

  @Test void server_compiles_yaml_template_to_validated_json_schema_over_stdio() throws Exception
  {
    Path jar = locateShadedJar();
    Process server = new ProcessBuilder("java", "-jar", jar.toString())
        .redirectErrorStream(false).start();
    StringBuilder stderr = drainStderr(server);
    BufferedReader stdout = new BufferedReader(
        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    try (Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
          + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"0\"}}}");
      readResponse(stdout, stderr);

      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

      // Realistic LLM-authored YAML: a template with a text-field child. Embedded
      // newlines are JSON-escaped because we're shipping this inside a JSON-RPC frame.
      String yamlBody = String.join("\\n",
          "type: template",
          "name: Patient demographics",
          "description: End-to-end YAML compilation test",
          "version: 0.1.0",
          "status: draft",
          "modelVersion: 1.6.0",
          "children:",
          "  - key: patient_name",
          "    type: text-field",
          "    name: Patient name",
          "    description: Free-text patient name");

      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"template_from_yaml\",\"arguments\":{\"yaml\":\""
          + yamlBody + "\"}}}");
      JsonNode response = readResponse(stdout, stderr);
      assertEquals(2, response.path("id").asInt());

      JsonNode result = response.path("result");
      assertFalse(result.path("isError").asBoolean(true),
          "tool should not report error; full response: " + response);

      String renderedText = result.path("content").get(0).path("text").asText();
      JsonNode parsed = jackson.readTree(renderedText);

      assertEquals("Patient demographics", parsed.path("schema:name").asText());
      assertEquals("0.1.0", parsed.path("pav:version").asText());
      assertTrue(parsed.path("properties").path("patient_name").isObject(),
          "patient_name child field must appear under properties; got: "
              + parsed.path("properties"));

      ValidationReport report = cedarValidator.validateTemplate(parsed);
      if (!"true".equals(report.getValidationStatus())) {
        StringBuilder msg = new StringBuilder(
            "CedarValidator rejected the YAML-compiled template returned over stdio:\n");
        for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
        fail(msg.toString());
      }
    } finally {
      shutdown(server, stderr);
    }
  }

  @Test void server_handles_sequential_tool_calls_on_one_session() throws Exception
  {
    // Three tool calls on the same server process: ping, then create_template, then
    // template_from_yaml. Catches the kind of bug where the first call works but a
    // subsequent call breaks because some state leaked between requests (the kind of
    // thing a fresh-server-per-call IT misses).
    Path jar = locateShadedJar();
    Process server = new ProcessBuilder("java", "-jar", jar.toString())
        .redirectErrorStream(false).start();
    StringBuilder stderr = drainStderr(server);
    BufferedReader stdout = new BufferedReader(
        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    try (Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
          + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"0\"}}}");
      readResponse(stdout, stderr);
      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

      // Call 1: ping
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"ping\",\"arguments\":{\"message\":\"first\"}}}");
      JsonNode r1 = readResponse(stdout, stderr);
      assertEquals(10, r1.path("id").asInt());
      assertEquals("pong: first",
          r1.path("result").path("content").get(0).path("text").asText());

      // Call 2: create_template
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"create_template\",\"arguments\":{\"name\":\"Second call\"}}}");
      JsonNode r2 = readResponse(stdout, stderr);
      assertEquals(11, r2.path("id").asInt());
      assertFalse(r2.path("result").path("isError").asBoolean(true),
          "create_template on second call should succeed; got: " + r2);

      // Call 3: template_from_yaml (the heavy one — exercises the full transcode
      // pipeline after two prior calls)
      String yamlBody = String.join("\\n",
          "type: template",
          "name: Third call",
          "description: Final call in sequence",
          "version: 0.1.0",
          "status: draft",
          "modelVersion: 1.6.0");
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"template_from_yaml\",\"arguments\":{\"yaml\":\""
          + yamlBody + "\"}}}");
      JsonNode r3 = readResponse(stdout, stderr);
      assertEquals(12, r3.path("id").asInt());
      assertFalse(r3.path("result").path("isError").asBoolean(true),
          "template_from_yaml on third call should succeed; got: " + r3);

      JsonNode parsed = jackson.readTree(
          r3.path("result").path("content").get(0).path("text").asText());
      assertEquals("Third call", parsed.path("schema:name").asText());

      // One more ping after the heavy call to confirm the server is still healthy.
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"ping\",\"arguments\":{\"message\":\"still alive\"}}}");
      JsonNode r4 = readResponse(stdout, stderr);
      assertEquals(13, r4.path("id").asInt());
      assertEquals("pong: still alive",
          r4.path("result").path("content").get(0).path("text").asText());
    } finally {
      shutdown(server, stderr);
    }
  }

  @Test void server_compiles_controlled_term_yaml_end_to_end() throws Exception
  {
    // The central CEDAR integration story over real stdio: a controlled-term field
    // bound to a class in an ontology. The tuple (iri, acronym, label, termLabel) is
    // exactly what bioportal-term-mcp emits — this is the end-to-end shape the
    // downstream LLM pipeline will exercise.
    Path jar = locateShadedJar();
    Process server = new ProcessBuilder("java", "-jar", jar.toString())
        .redirectErrorStream(false).start();
    StringBuilder stderr = drainStderr(server);
    BufferedReader stdout = new BufferedReader(
        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    try (Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
          + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"0\"}}}");
      readResponse(stdout, stderr);
      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

      String yaml = String.join("\\n",
          "type: template",
          "name: Diagnosis template",
          "description: Template with a controlled-term diagnosis field",
          "version: 0.1.0",
          "status: draft",
          "modelVersion: 1.6.0",
          "children:",
          "  - key: diagnosis",
          "    type: controlled-term-field",
          "    name: Primary diagnosis",
          "    description: Diagnosis from the Human Disease Ontology",
          "    datatype: iri",
          "    values:",
          "      - type: class",
          "        label: disease",
          "        acronym: DOID",
          "        termType: class",
          "        termLabel: disease",
          "        iri: http://purl.obolibrary.org/obo/DOID_4");

      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"template_from_yaml\",\"arguments\":{\"yaml\":\""
          + yaml + "\"}}}");
      JsonNode response = readResponse(stdout, stderr);
      assertEquals(2, response.path("id").asInt());

      JsonNode result = response.path("result");
      assertFalse(result.path("isError").asBoolean(true),
          "controlled-term YAML must compile cleanly over stdio; got: " + response);

      JsonNode parsed = jackson.readTree(result.path("content").get(0).path("text").asText());
      assertTrue(parsed.path("properties").path("diagnosis").isObject(),
          "diagnosis field must appear under properties; got keys: "
              + parsed.path("properties"));

      // Re-validate end-to-end: the JSON coming back over the wire must satisfy
      // CedarValidator independently of the in-handler validation step.
      ValidationReport report = cedarValidator.validateTemplate(parsed);
      if (!"true".equals(report.getValidationStatus())) {
        StringBuilder msg = new StringBuilder(
            "CedarValidator rejected the controlled-term template returned over stdio:\n");
        for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
        fail(msg.toString());
      }
    } finally {
      shutdown(server, stderr);
    }
  }

  @Test void server_returns_error_result_for_blank_name() throws Exception
  {
    Path jar = locateShadedJar();
    Process server = new ProcessBuilder("java", "-jar", jar.toString())
        .redirectErrorStream(false).start();
    StringBuilder stderr = drainStderr(server);
    BufferedReader stdout = new BufferedReader(
        new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    try (Writer stdin = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
          + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
          + "\"clientInfo\":{\"name\":\"e2e\",\"version\":\"0\"}}}");
      readResponse(stdout, stderr);

      send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

      send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
          + "{\"name\":\"create_template\",\"arguments\":{\"name\":\"   \"}}}");
      JsonNode response = readResponse(stdout, stderr);

      assertEquals(2, response.path("id").asInt());
      // Bad inputs surface as isError=true content (per DESIGN.md Principle 5),
      // not as JSON-RPC protocol errors.
      assertTrue(response.has("result"),
          "bad input should still produce a result envelope; got " + response);
      assertTrue(response.path("result").path("isError").asBoolean(),
          "blank name should produce isError=true; got " + response);
    } finally {
      shutdown(server, stderr);
    }
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static Path locateShadedJar() throws IOException
  {
    Path targetDir = Paths.get("target").toAbsolutePath();
    if (!Files.isDirectory(targetDir))
      fail("target/ directory missing — run `mvn package` before `mvn verify`");
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(targetDir, "*-all.jar")) {
      for (Path p : ds) return p;
    }
    fail("no shaded -all.jar found in " + targetDir + "; failsafe should run after the shade plugin");
    throw new AssertionError("unreachable");
  }

  private static void send(Writer stdin, String json) throws IOException
  {
    stdin.write(json);
    stdin.write('\n');
    stdin.flush();
  }

  /**
   * Reads one JSON-RPC response from the server's stdout, with a bounded timeout so a
   * silently-hung server fails fast rather than blocking the test forever. On
   * timeout or parse failure the captured stderr is attached to the assertion message
   * — that's where the JVM prints startup exceptions and SLF4J messages.
   */
  private JsonNode readResponse(BufferedReader stdout, StringBuilder stderr) throws Exception
  {
    CompletableFuture<String> lineFuture = CompletableFuture.supplyAsync(() -> {
      try {
        return stdout.readLine();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    String line;
    try {
      line = lineFuture.get(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException te) {
      fail("timed out waiting for server response. Captured stderr:\n" + stderrSnapshot(stderr));
      throw new AssertionError("unreachable");
    } catch (ExecutionException ee) {
      fail("stdout read failed: " + ee.getCause()
          + ". Captured stderr:\n" + stderrSnapshot(stderr));
      throw new AssertionError("unreachable");
    }
    if (line == null)
      fail("server closed stdout before sending a response. Captured stderr:\n"
          + stderrSnapshot(stderr));
    try {
      return jackson.readTree(line);
    } catch (IOException ioe) {
      fail("server emitted non-JSON line on stdout: '" + line + "'. "
          + "Captured stderr:\n" + stderrSnapshot(stderr));
      throw new AssertionError("unreachable");
    }
  }

  private static String stderrSnapshot(StringBuilder stderr)
  {
    synchronized (stderr) { return stderr.toString(); }
  }

  private static StringBuilder drainStderr(Process server)
  {
    StringBuilder stderr = new StringBuilder();
    Thread t = new Thread(() -> {
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(server.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null)
          synchronized (stderr) { stderr.append(line).append('\n'); }
      } catch (IOException ignored) {}
    }, "stderr-pump");
    t.setDaemon(true);
    t.start();
    return stderr;
  }

  private static void shutdown(Process server, StringBuilder stderr) throws InterruptedException
  {
    // The stdio server's main thread sits on Thread.currentThread().join(), so closing
    // stdin alone may not let the JVM exit. Try a graceful destroy first, then force.
    server.destroy();
    if (!server.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      server.destroyForcibly();
      server.waitFor(5, TimeUnit.SECONDS);
    }
  }
}

package org.metadatacenter.artifacts.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.artifacts.mcp.tools.TemplateFromYamlTool;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration test that drives {@code template_from_yaml} against a real-world
 * battery of CEDAR templates — by default, the HuBMAP template library at
 * <a href="https://github.com/hubmapconsortium/dataset-metadata-spreadsheet">github.com/hubmapconsortium/dataset-metadata-spreadsheet</a>.
 *
 * <p>The test loops over every {@code *.yaml} file in the configured directory,
 * runs it through {@link TemplateFromYamlTool#handler}, and asserts both that
 * the tool returns a non-error result and that {@link CedarValidator} accepts the
 * rendered JSON Schema. It also confirms that the paired {@code *.json} file
 * (when present) is itself accepted by {@link CedarValidator}, so a failure on
 * "our compiled output" vs "the canonical paired JSON" can be attributed to the
 * right side of the comparison.
 *
 * <p>Gated by the {@code hubmap.templates.dir} system property; skip when unset.
 * The default {@code mvn verify} run on a machine without HuBMAP templates checked
 * out will not fail. Enable with:
 *
 * <pre>{@code
 * mvn verify -Dhubmap.templates.dir=/path/to/HuBMAP/templates
 * }</pre>
 *
 * <p>The constructor / argument source intentionally produces an {@link Arguments}
 * pair per YAML file so test failures are reported per template name in the
 * surefire output, not as a single "battery failed" error.
 */
@EnabledIfSystemProperty(named = "hubmap.templates.dir", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class HubmapTemplatesIT
{
  private final ObjectMapper jackson = new ObjectMapper();
  private final ModelValidator cedarValidator = new CedarValidator();

  // Per-run summary, dumped on @AfterAll. Useful when many files in the battery
  // share the same root cause — the summary makes the pattern obvious.
  private final List<String> passes = new ArrayList<>();
  private final List<String> failures = new ArrayList<>();

  @BeforeAll void announceBattery()
  {
    String dir = System.getProperty("hubmap.templates.dir");
    System.out.println("[HubmapTemplatesIT] running against " + dir);
  }

  @AfterAll void printSummary()
  {
    int total = passes.size() + failures.size();
    System.out.println("[HubmapTemplatesIT] summary: "
        + passes.size() + "/" + total + " templates compiled and validated");
    if (!failures.isEmpty()) {
      System.out.println("[HubmapTemplatesIT] failures:");
      for (String f : failures) System.out.println("  - " + f);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("templates")
  void compiles_and_validates_template_from_yaml(String displayName, Path yamlFile, Path pairedJson)
      throws Exception
  {
    String yamlText = Files.readString(yamlFile);

    McpSchema.CallToolResult result = invoke(yamlText);

    if (result.isError()) {
      String detail = ((McpSchema.TextContent) result.content().get(0)).text();
      failures.add(displayName + " — handler error: " + truncate(detail, 200));
      fail("template_from_yaml returned isError=true for " + yamlFile.getFileName() + ":\n  " + detail);
    }

    String json = ((McpSchema.TextContent) result.content().get(0)).text();
    JsonNode parsed;
    try {
      parsed = jackson.readTree(json);
    } catch (Exception e) {
      failures.add(displayName + " — output not parseable as JSON");
      throw e;
    }

    ValidationReport report = cedarValidator.validateTemplate(parsed);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder(
          "CedarValidator rejected the compiled output for " + yamlFile.getFileName() + ":\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      failures.add(displayName + " — CedarValidator rejected compiled output");
      fail(msg.toString());
    }

    // If the paired *.json file exists, also confirm it validates (and so does
    // the version we compiled). A divergence here would say "our compiled
    // output validates but the canonical paired JSON doesn't", which is data
    // we'd want to know.
    if (pairedJson != null && Files.exists(pairedJson)) {
      JsonNode authoritative = jackson.readTree(Files.readString(pairedJson));
      ValidationReport authReport = cedarValidator.validateTemplate(authoritative);
      assertEquals("true", authReport.getValidationStatus(),
          "paired JSON " + pairedJson.getFileName() + " unexpectedly fails CedarValidator");
    }

    passes.add(displayName);
  }

  // ---------------------------------------------------------------------
  // argument source: every *.yaml file in the configured directory
  // ---------------------------------------------------------------------

  Stream<Arguments> templates() throws Exception
  {
    String dirProp = System.getProperty("hubmap.templates.dir");
    Path dir = Paths.get(dirProp);
    assertTrue(Files.isDirectory(dir),
        "hubmap.templates.dir does not point at a directory: " + dirProp);

    List<Arguments> args = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.yaml")) {
      for (Path yaml : ds) {
        String stem = yaml.getFileName().toString();
        String displayName = stem.substring(0, stem.length() - ".yaml".length());
        Path pairedJson = dir.resolve(displayName + ".json");
        args.add(Arguments.of(displayName, yaml, pairedJson));
      }
    }
    args.sort((a, b) -> ((String) a.get()[0]).compareTo((String) b.get()[0]));
    return args.stream();
  }

  // ---------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(String yaml)
  {
    return TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
  }

  private static String truncate(String s, int limit)
  {
    return s.length() <= limit ? s : s.substring(0, limit) + "...";
  }
}

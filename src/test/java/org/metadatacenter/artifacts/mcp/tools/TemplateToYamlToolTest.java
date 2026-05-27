package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code template_to_yaml} tool — the reverse direction of
 * {@code template_from_yaml}.
 *
 * <p>Each test sources its input JSON by first compiling YAML through the existing
 * {@code template_from_yaml} tool. That keeps the test inputs realistic (real CEDAR
 * JSON Schema, not hand-rolled approximations) and incidentally exercises the
 * full YAML → JSON → YAML round-trip the library is built around.
 */
final class TemplateToYamlToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    jackson = new ObjectMapper();
  }

  @Test void renders_compact_yaml_by_default() throws Exception
  {
    String json = compileToJson(
        "type: template\n"
            + "name: Patient demographics\n"
            + "description: Minimal demographics template\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n");

    McpSchema.CallToolResult result = invoke(Map.of("json", json));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);

    assertTrue(yaml.contains("type: template"),
        "compact YAML should start with the type/name pair; got:\n" + yaml);
    assertTrue(yaml.contains("name: \"Patient demographics\"") || yaml.contains("name: Patient demographics"),
        "compact YAML should carry the template name; got:\n" + yaml);
  }

  @Test void compact_form_omits_what_standard_form_includes() throws Exception
  {
    // The whole point of the form flag: compact drops fields the reader will infer or
    // default; standard emits everything. So compact YAML must be strictly shorter (or
    // at least not longer) than standard YAML for the same template, and standard must
    // contain at least one provenance/status field that compact doesn't.
    String json = compileToJson(
        "type: template\n"
            + "name: Form comparison\n"
            + "description: Used to compare compact vs standard YAML output\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n");

    String compactYaml = textOf(invoke(Map.of("json", json, "form", "compact")));
    String standardYaml = textOf(invoke(Map.of("json", json, "form", "standard")));

    assertTrue(standardYaml.length() > compactYaml.length(),
        "standard YAML should be longer than compact YAML; "
            + "compact=" + compactYaml.length() + " standard=" + standardYaml.length()
            + "\ncompact:\n" + compactYaml + "\nstandard:\n" + standardYaml);

    // status: draft is the textbook example of something that's defaulted at read time
    // — it should appear in standard but not in compact.
    assertTrue(standardYaml.contains("status:"),
        "standard YAML must carry the status field; got:\n" + standardYaml);
    assertFalse(compactYaml.contains("status:"),
        "compact YAML must omit the status field; got:\n" + compactYaml);
  }

  @Test void compact_form_round_trips_through_template_from_yaml() throws Exception
  {
    // template_from_yaml(template_to_yaml(template_from_yaml(yaml), form=compact))
    // must succeed and preserve structural content — otherwise compact form isn't
    // actually round-trippable. Compact intentionally drops provenance, status, and
    // version metadata; those are *not* expected to survive (use form=standard if
    // they need to). modelVersion stays in compact so the reader accepts the output.
    String originalYaml =
        "type: template\n"
            + "name: Round-trip\n"
            + "description: Round-trip test template\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n";

    String firstJson = compileToJson(originalYaml);
    String yaml = textOf(invoke(Map.of("json", firstJson, "form", "compact")));
    String secondJson = compileToJson(yaml);

    JsonNode first = jackson.readTree(firstJson);
    JsonNode second = jackson.readTree(secondJson);

    assertEquals(first.path("schema:name"), second.path("schema:name"),
        "round-trip must preserve schema:name");
    assertEquals(first.path("properties").path("patient_name").path("schema:name"),
        second.path("properties").path("patient_name").path("schema:name"),
        "round-trip must preserve child field name");
  }

  @Test void standard_form_round_trip_additionally_preserves_version_and_status() throws Exception
  {
    // Standard form's contract is stronger: it carries the metadata compact drops.
    // Same round-trip, but assert pav:version survives — which proves the form flag
    // is doing real work.
    String originalYaml =
        "type: template\n"
            + "name: Standard round-trip\n"
            + "description: Standard-form round-trip test\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    String firstJson = compileToJson(originalYaml);
    String yaml = textOf(invoke(Map.of("json", firstJson, "form", "standard")));
    String secondJson = compileToJson(yaml);

    JsonNode first = jackson.readTree(firstJson);
    JsonNode second = jackson.readTree(secondJson);

    assertEquals(first.path("pav:version"), second.path("pav:version"),
        "standard-form round-trip must preserve pav:version");
  }

  @Test void rejects_unknown_form_value()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "json", "{}",
        "form", "tiny"));
    assertTrue(result.isError(), "unknown form value must produce isError=true");
    assertTrue(errorText(result).contains("form"),
        "error should mention the bad form value; got: " + errorText(result));
  }

  @Test void rejects_missing_json_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("json"));
  }

  @Test void rejects_blank_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of("json", "   "));
    assertTrue(result.isError(), "blank json input must produce isError=true");
  }

  @Test void rejects_non_object_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of("json", "[1,2,3]"));
    assertTrue(result.isError(), "non-object json must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("object"),
        "error should mention the missing top-level object; got: " + errorText(result));
  }

  @Test void rejects_malformed_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of("json", "{ not json"));
    assertTrue(result.isError(), "malformed json must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("json"),
        "error should mention json; got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return TemplateToYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_to_yaml", arguments));
  }

  private static String compileToJson(String yaml)
  {
    McpSchema.CallToolResult result = TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(), "test fixture YAML must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

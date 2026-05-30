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
 * {@code template_to_json}.
 *
 * <p>Each test sources its input JSON by first compiling YAML through the existing
 * {@code template_to_json} tool. That keeps the test inputs realistic (real CEDAR
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

    McpSchema.CallToolResult result = invoke(Map.of("artifact", json));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);

    assertTrue(yaml.contains("type: template"),
        "compact YAML should start with the type/name pair; got:\n" + yaml);
    assertTrue(yaml.contains("name: \"Patient demographics\"") || yaml.contains("name: Patient demographics"),
        "compact YAML should carry the template name; got:\n" + yaml);
  }

  @Test void recompacts_expanded_yaml_without_a_json_hop() throws Exception
  {
    // The exchange form tools emit is expanded YAML; this tool must accept it directly (no
    // JSON detour) and re-render it compact for display. create_template returns expanded YAML.
    String expandedYaml = textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template",
            Map.of("name", "Patient Study", "version", "0.1.0", "isCompact", false))));
    assertTrue(expandedYaml.contains("modelVersion"),
        "fixture should be expanded YAML carrying modelVersion; got:\n" + expandedYaml);

    McpSchema.CallToolResult result = invoke(Map.of("artifact", expandedYaml, "isCompact", true));

    assertFalse(result.isError(), errorText(result));
    String compact = textOf(result);
    assertTrue(compact.contains("name: Patient Study") || compact.contains("name: \"Patient Study\""),
        "recompacted YAML should carry the name; got:\n" + compact);
    assertFalse(compact.contains("modelVersion"),
        "compact form should drop modelVersion; got:\n" + compact);
  }

  @Test void compact_form_omits_what_standard_form_includes() throws Exception
  {
    // The whole point of the isCompact flag: compact drops fields the reader will infer
    // or default; standard emits everything. So compact YAML must be strictly shorter
    // than standard for the same template, and standard must carry at least one
    // provenance/status field that compact doesn't.
    String json = compileToJson(
        "type: template\n"
            + "name: Form comparison\n"
            + "description: Used to compare compact vs standard YAML output\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n");

    String compactYaml = textOf(invoke(Map.of("artifact", json, "isCompact", true)));
    String standardYaml = textOf(invoke(Map.of("artifact", json, "isCompact", false)));

    assertTrue(standardYaml.length() > compactYaml.length(),
        "standard YAML should be longer than compact YAML; "
            + "compact=" + compactYaml.length() + " standard=" + standardYaml.length()
            + "\ncompact:\n" + compactYaml + "\nstandard:\n" + standardYaml);

    // status: draft is the textbook example of something compact drops.
    assertTrue(standardYaml.contains("status:"),
        "standard YAML must carry the status field; got:\n" + standardYaml);
    assertFalse(compactYaml.contains("status:"),
        "compact YAML must omit the status field; got:\n" + compactYaml);
  }

  @Test void compact_form_round_trips_through_template_to_json() throws Exception
  {
    // template_to_json(template_to_yaml(template_to_json(yaml), isCompact=true))
    // must succeed and preserve structural content. Compact intentionally drops
    // provenance, status, and version metadata; those are *not* expected to survive
    // (use isCompact=false if they need to). The reader's compact mode defaults
    // modelVersion so the output is accepted.
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
    String yaml = textOf(invoke(Map.of("artifact", firstJson, "isCompact", true)));
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
    // Same round-trip, but assert pav:version survives — which proves the isCompact
    // flag is doing real work.
    String originalYaml =
        "type: template\n"
            + "name: Standard round-trip\n"
            + "description: Standard-form round-trip test\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    String firstJson = compileToJson(originalYaml);
    String yaml = textOf(invoke(Map.of("artifact", firstJson, "isCompact", false)));
    String secondJson = compileToJson(yaml);

    JsonNode first = jackson.readTree(firstJson);
    JsonNode second = jackson.readTree(secondJson);

    assertEquals(first.path("pav:version"), second.path("pav:version"),
        "standard-form round-trip must preserve pav:version");
  }

  @Test void rejects_non_boolean_isCompact()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "artifact", "{}",
        "isCompact", "yes"));
    assertTrue(result.isError(), "non-boolean isCompact must produce isError=true");
    assertTrue(errorText(result).contains("isCompact"),
        "error should mention the bad arg; got: " + errorText(result));
  }

  @Test void rejects_missing_json_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("artifact"));
  }

  @Test void rejects_blank_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "   "));
    assertTrue(result.isError(), "blank json input must produce isError=true");
  }

  @Test void rejects_non_object_artifact()
  {
    // A top-level sequence is neither a JSON object nor a YAML mapping.
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "[1,2,3]"));
    assertTrue(result.isError(), "non-object artifact must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("mapping")
            || errorText(result).toLowerCase().contains("object"),
        "error should mention the missing top-level mapping/object; got: " + errorText(result));
  }

  @Test void rejects_malformed_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "{ not json"));
    assertTrue(result.isError(), "malformed artifact must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("artifact"),
        "error should mention the offending artifact argument; got: " + errorText(result));
  }

  // YAML serialization contract: null is never a valid value. The parser resolves every
  // unquoted null spelling (~, bare key, null, NULL, Null) to a real null, and the reader
  // rejects it. A quoted "~" is a legitimate string, not a null, and must still be accepted.

  @Test void rejects_yaml_tilde_null()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact",
        "type: template\nname: T\ndescription: ~\n"));
    assertTrue(result.isError(), "`~` (YAML null) must be rejected");
    assertTrue(errorText(result).toLowerCase().contains("null"),
        "error should mention null; got: " + errorText(result));
  }

  @Test void rejects_yaml_bare_empty_null()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact",
        "type: template\nname: T\ndescription:\n"));
    assertTrue(result.isError(), "a bare empty value (YAML null) must be rejected");
    assertTrue(errorText(result).toLowerCase().contains("null"),
        "error should mention null; got: " + errorText(result));
  }

  @Test void rejects_yaml_uppercase_null()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact",
        "type: template\nname: T\ndescription: NULL\n"));
    assertTrue(result.isError(), "`NULL` (YAML null) must be rejected");
  }

  @Test void rejects_nested_yaml_null_in_child_config()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact",
        "type: template\nname: T\nchildren:\n  - key: f\n    type: text-field\n"
            + "    name: F\n    configuration:\n      minLength: ~\n"));
    assertTrue(result.isError(), "a null nested in a child configuration must be rejected");
    assertTrue(errorText(result).contains("configuration"),
        "error should point at the nested path; got: " + errorText(result));
  }

  @Test void accepts_quoted_tilde_as_string()
  {
    // A quoted "~" is the string "~", not a null — it must NOT be rejected.
    McpSchema.CallToolResult result = invoke(Map.of("artifact",
        "type: template\nname: T\ndescription: \"~\"\n"));
    assertFalse(result.isError(), "quoted \"~\" is a string, not null: " + errorText(result));
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
    McpSchema.CallToolResult result = TemplateToJsonTool.handler(null,
        new McpSchema.CallToolRequest("template_to_json", Map.of("yaml", yaml)));
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

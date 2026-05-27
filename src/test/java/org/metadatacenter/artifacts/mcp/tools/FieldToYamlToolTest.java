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
 * Tests for the {@code field_to_yaml} tool. Mirrors {@link TemplateToYamlToolTest}'s
 * shape: source the input JSON by compiling YAML through {@code field_from_yaml}
 * and round-trip back through {@code field_from_yaml}.
 */
final class FieldToYamlToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    jackson = new ObjectMapper();
  }

  @Test void renders_compact_yaml_by_default() throws Exception
  {
    String json = compileToJson(
        "type: text-field\n"
            + "name: Patient name\n"
            + "description: Free-text patient name\n"
            + "modelVersion: 1.6.0\n");

    McpSchema.CallToolResult result = invoke(Map.of("json", json));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);

    assertTrue(yaml.contains("type: text-field"),
        "compact YAML should declare type: text-field; got:\n" + yaml);
    assertTrue(yaml.contains("Patient name"),
        "compact YAML should carry the field name; got:\n" + yaml);
    assertFalse(yaml.contains("status:"),
        "compact YAML must omit the status field; got:\n" + yaml);
  }

  @Test void compact_form_round_trips_through_field_from_yaml() throws Exception
  {
    String originalYaml =
        "type: controlled-term-field\n"
            + "name: Primary diagnosis\n"
            + "description: Diagnosis from the Human Disease Ontology\n"
            + "modelVersion: 1.6.0\n"
            + "datatype: iri\n"
            + "values:\n"
            + "  - type: class\n"
            + "    label: disease\n"
            + "    acronym: DOID\n"
            + "    termType: class\n"
            + "    termLabel: disease\n"
            + "    iri: http://purl.obolibrary.org/obo/DOID_4\n";

    String firstJson = compileToJson(originalYaml);
    String yaml = textOf(invoke(Map.of("json", firstJson, "isCompact", true)));
    String secondJson = compileToJson(yaml);

    JsonNode first = jackson.readTree(firstJson);
    JsonNode second = jackson.readTree(secondJson);

    assertEquals(first.path("schema:name"), second.path("schema:name"),
        "round-trip must preserve schema:name");
  }

  @Test void rejects_missing_json_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("json"));
  }

  @Test void rejects_non_object_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of("json", "[1,2,3]"));
    assertTrue(result.isError(), "non-object json must produce isError=true");
  }

  @Test void rejects_non_boolean_isCompact()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "json", "{}",
        "isCompact", "yes"));
    assertTrue(result.isError(), "non-boolean isCompact must produce isError=true");
    assertTrue(errorText(result).contains("isCompact"),
        "error should mention the bad arg; got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return FieldToYamlTool.handler(null,
        new McpSchema.CallToolRequest("field_to_yaml", arguments));
  }

  private static String compileToJson(String yaml)
  {
    McpSchema.CallToolResult result = FieldFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("field_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "test fixture YAML must compile cleanly; got: " + errorText(result));
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

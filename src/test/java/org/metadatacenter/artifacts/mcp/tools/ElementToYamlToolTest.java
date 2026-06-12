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
 * Tests for the {@code element_to_yaml} tool. Mirrors {@link TemplateToYamlToolTest}'s
 * shape: source the input JSON by compiling YAML through {@code element_to_json}
 * (so the inputs are real CEDAR JSON Schema, not hand-rolled approximations) and
 * round-trip back through {@code element_to_json} to prove the contract holds.
 */
final class ElementToYamlToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    jackson = new ObjectMapper();
  }

  @Test void renders_compact_yaml_by_default() throws Exception
  {
    String json = compileToJson(
        "type: element\n"
            + "name: Address\n"
            + "description: Postal address element\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: street\n"
            + "    type: text-field\n"
            + "    name: Street\n");

    McpSchema.CallToolResult result = invoke(Map.of("artifact", json));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);

    assertTrue(yaml.contains("type: element"),
        "compact YAML should declare type: element; got:\n" + yaml);
    assertTrue(yaml.contains("Address"),
        "compact YAML should carry the element name; got:\n" + yaml);
    assertFalse(yaml.contains("status:"),
        "compact YAML must omit the status field; got:\n" + yaml);
  }

  @Test void compact_form_round_trips_through_element_to_json() throws Exception
  {
    String originalYaml =
        "type: element\n"
            + "name: Round-trip element\n"
            + "description: Round-trip test\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: street\n"
            + "    type: text-field\n"
            + "    name: Street\n";

    String firstJson = compileToJson(originalYaml);
    String yaml = textOf(invoke(Map.of("artifact", firstJson, "isCompact", true)));
    String secondJson = compileToJson(yaml);

    JsonNode first = jackson.readTree(firstJson);
    JsonNode second = jackson.readTree(secondJson);

    assertEquals(first.path("schema:name"), second.path("schema:name"),
        "round-trip must preserve schema:name");
    assertEquals(first.path("properties").path("street").path("schema:name"),
        second.path("properties").path("street").path("schema:name"),
        "round-trip must preserve child field name");
  }

  @Test void rejects_missing_json_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("artifact"));
  }

  @Test void rejects_non_object_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "[1,2,3]"));
    assertTrue(result.isError(), "non-object json must produce isError=true");
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

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return ElementToYamlTool.handler(null,
        new McpSchema.CallToolRequest("element_to_yaml", arguments));
  }

  private static String compileToJson(String yaml)
  {
    McpSchema.CallToolResult result = ElementToJsonTool.handler(null,
        new McpSchema.CallToolRequest("element_to_json", Map.of("artifact", yaml)));
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

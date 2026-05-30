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

final class InstanceToYamlToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void renders_compact_yaml_by_default() throws Exception
  {
    String json = compileToJson(
        "type: instance\n"
            + "name: Patient 42\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/abc-123\n");

    McpSchema.CallToolResult result = invoke(Map.of("json", json));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);

    assertTrue(yaml.contains("type: instance"),
        "compact YAML should declare type: instance; got:\n" + yaml);
    assertTrue(yaml.contains("Patient 42"),
        "compact YAML should carry the instance name; got:\n" + yaml);
    assertTrue(yaml.contains("isBasedOn:"),
        "compact YAML must carry isBasedOn; got:\n" + yaml);
  }

  @Test void compact_form_round_trips_through_instance_to_json() throws Exception
  {
    String originalYaml =
        "type: instance\n"
            + "name: Round-trip\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/abc-123\n";

    String firstJson = compileToJson(originalYaml);
    String yaml = textOf(invoke(Map.of("json", firstJson, "isCompact", true)));
    String secondJson = compileToJson(yaml);

    JsonNode first = jackson.readTree(firstJson);
    JsonNode second = jackson.readTree(secondJson);

    assertEquals(first.path("schema:name"), second.path("schema:name"),
        "round-trip must preserve schema:name");
    assertEquals(first.path("schema:isBasedOn"), second.path("schema:isBasedOn"),
        "round-trip must preserve schema:isBasedOn");
  }

  @Test void rejects_non_boolean_isCompact()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "json", "{}",
        "isCompact", "yes"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("isCompact"));
  }

  @Test void rejects_missing_json_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("json"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return InstanceToYamlTool.handler(null,
        new McpSchema.CallToolRequest("instance_to_yaml", args));
  }

  private static String compileToJson(String yaml)
  {
    McpSchema.CallToolResult result = InstanceToJsonTool.handler(null,
        new McpSchema.CallToolRequest("instance_to_json", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture instance YAML must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content());
    assertFalse(result.content().isEmpty());
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

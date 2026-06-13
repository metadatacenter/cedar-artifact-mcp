package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code instance_artifact_to_yaml} — renders a template instance or an element instance
 * (auto-detected, from YAML or JSON) as YAML.
 */
final class InstanceArtifactToYamlToolTest
{
  @Test void renders_a_template_instance()
  {
    String instance = textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", createTemplate("Demographics"))));
    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", instance));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("type: instance"),
        "must render a template instance; got:\n" + textOf(result));
  }

  @Test void renders_an_element_instance()
  {
    // The gap this unification fills: an element instance had no YAML re-render path before.
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", addressElement())));
    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", entry));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("type: element-instance"),
        "must render an element instance; got:\n" + textOf(result));
  }

  @Test void renders_an_element_instance_from_its_json()
  {
    // JSON-input detection: an element instance JSON has no schema:isBasedOn, so it is recognized
    // as an element instance and rendered back to YAML.
    String element = addressElement();
    String entryYaml = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", element)));
    String entryJson = textOf(invokeTool(InstanceArtifactToJsonTool::handler, "instance_artifact_to_json",
        Map.of("instance_artifact", entryYaml, "schema_artifact", element)));

    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", entryJson));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("type: element-instance"),
        "an element instance JSON must render back to YAML; got:\n" + textOf(result));
  }

  @Test void rejects_missing_instance()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("instance_artifact"));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return InstanceArtifactToYamlTool.handler(null,
        new McpSchema.CallToolRequest("instance_artifact_to_yaml", args));
  }

  private static String createTemplate(String name)
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", name)));
  }

  /** An element named Address carrying one text field, Street. */
  private static String addressElement()
  {
    Map<String, Object> fieldArgs = new LinkedHashMap<>();
    fieldArgs.put("name", "Street");
    fieldArgs.put("type", "text-field");
    String street = textOf(invokeTool(CreateFieldTool::handler, "create_field", fieldArgs));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", street,
        "key", "Street")));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(McpSyncServerExchange e, McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture '" + name + "' must succeed; got: " + errorText(result));
    return result;
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

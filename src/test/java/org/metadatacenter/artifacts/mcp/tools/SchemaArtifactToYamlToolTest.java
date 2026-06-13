package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code schema_artifact_to_yaml} — the auto-detecting schema → YAML renderer. It
 * detects template / element / field (from YAML or JSON), honors {@code isCompact}, and redirects
 * instances to {@code instance_artifact_to_yaml}.
 */
final class SchemaArtifactToYamlToolTest
{
  @Test void renders_a_template_compact_by_default()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createTemplate("Demographics")));
    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("type: template"), "must render a template; got:\n" + yaml);
    assertFalse(yaml.contains("modelVersion"),
        "compact (default) drops modelVersion; got:\n" + yaml);
  }

  @Test void expanded_form_keeps_provenance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "artifact", createTemplate("Demographics"), "isCompact", false));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("modelVersion"),
        "expanded form must keep modelVersion; got:\n" + textOf(result));
  }

  @Test void renders_an_element()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createElement("Address")));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("type: element"));
  }

  @Test void renders_a_field()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createField("Patient name", "text-field")));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("type: text-field"));
  }

  @Test void imports_a_json_schema_into_yaml()
  {
    // Feed the JSON Schema rendering back in: it must come out as YAML (round-trip via JSON).
    String templateJson = textOf(invokeTool(SchemaArtifactToJsonTool::handler, "schema_artifact_to_json",
        Map.of("artifact", createTemplate("Demographics"))));
    McpSchema.CallToolResult result = invoke(Map.of("artifact", templateJson));
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("type: template"),
        "a JSON Schema template must import to YAML; got:\n" + textOf(result));
  }

  @Test void redirects_an_instance()
  {
    String instance = textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", createTemplate("Demographics"))));
    McpSchema.CallToolResult result = invoke(Map.of("artifact", instance));
    assertTrue(result.isError(), "an instance is not a schema artifact");
    assertTrue(errorText(result).contains("instance_artifact_to_yaml"),
        "redirect should name instance_artifact_to_yaml; got: " + errorText(result));
  }

  @Test void rejects_missing_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("artifact"));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SchemaArtifactToYamlTool.handler(null,
        new McpSchema.CallToolRequest("schema_artifact_to_yaml", args));
  }

  private static String createTemplate(String name)
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", name)));
  }

  private static String createElement(String name)
  {
    return textOf(invokeTool(CreateElementTool::handler, "create_element", Map.of("name", name)));
  }

  private static String createField(String name, String type)
  {
    return textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", name, "type", type)));
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

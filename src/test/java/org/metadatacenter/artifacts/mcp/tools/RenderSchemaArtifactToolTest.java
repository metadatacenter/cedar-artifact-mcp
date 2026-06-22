package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code render_schema_artifact} — the auto-detecting schema renderer. It detects
 * template / element / field from the YAML {@code type:} (or JSON {@code @type}) and renders to
 * YAML (default) or JSON, minting a top-level {@code @id} when absent. No CedarValidator runs.
 * Instances are redirected to {@code render_instance_artifact}.
 */
final class RenderSchemaArtifactToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void renders_yaml_by_default() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("schema_artifact", createTemplate("Demographics")));
    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("type: template"),
        "default output must be YAML with a template discriminator; got:\n" + yaml);
    assertFalse(yaml.stripLeading().startsWith("{"), "default output must not be JSON; got:\n" + yaml);
  }

  @Test void renders_json_when_format_json() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", createTemplate("Demographics"), "format", "json"));
    assertFalse(result.isError(), errorText(result));
    JsonNode json = jackson.readTree(textOf(result));
    assertEquals("https://schema.metadatacenter.org/core/Template", json.path("@type").asText(),
        "a template must render with the template @type; got:\n" + json.toPrettyString());
  }

  @Test void renders_an_element_and_a_field_json() throws Exception
  {
    assertEquals("https://schema.metadatacenter.org/core/TemplateElement",
        jackson.readTree(textOf(invoke(Map.of(
            "schema_artifact", createElement("Address"), "format", "json")))).path("@type").asText());
    assertEquals("https://schema.metadatacenter.org/core/TemplateField",
        jackson.readTree(textOf(invoke(Map.of(
            "schema_artifact", createField("Patient name", "text-field"), "format", "json"))))
            .path("@type").asText());
  }

  @Test void compact_true_yaml_drops_provenance() throws Exception
  {
    String expanded = textOf(invoke(Map.of(
        "schema_artifact", createTemplate("Demographics"), "compact", false)));
    String compact = textOf(invoke(Map.of(
        "schema_artifact", createTemplate("Demographics"), "compact", true)));
    assertTrue(expanded.contains("status:") || expanded.contains("version:"),
        "the expanded form should carry provenance; got:\n" + expanded);
    assertFalse(compact.contains("status: draft"),
        "the compact form should omit status; got:\n" + compact);
  }

  @Test void compact_with_json_is_an_error()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", createTemplate("Demographics"), "format", "json", "compact", true));
    assertTrue(result.isError(), "compact + json must be a tool error");
    assertTrue(errorText(result).toLowerCase().contains("compact"),
        "the error should mention compact; got: " + errorText(result));
  }

  @Test void mints_a_top_level_id_when_absent_json() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", "type: text-field\nname: Bare\n", "format", "json"));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("@id").asText().contains("/template-fields/"),
        "a field with no id must be minted a template-fields IRI; got:\n" + textOf(result));
  }

  @Test void redirects_an_instance() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("schema_artifact",
        "type: instance\nname: P1\nisBasedOn: https://repo.metadatacenter.org/templates/x\n"));
    assertTrue(result.isError(), "an instance is not a schema artifact");
    assertTrue(errorText(result).contains("render_instance_artifact"),
        "redirect should name render_instance_artifact; got: " + errorText(result));
  }

  @Test void rejects_missing_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("schema_artifact"));
  }

  @Test void rejects_unknown_format()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", createField("X", "text-field"), "format", "xml"));
    assertTrue(result.isError(), "an unknown format must be a tool error");
    assertTrue(errorText(result).contains("format"), "error should mention format; got: " + errorText(result));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return RenderSchemaArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_schema_artifact", args));
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

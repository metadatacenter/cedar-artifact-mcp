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
 * Tests for {@code schema_artifact_to_json} — the auto-detecting schema renderer. It detects
 * template / element / field from the YAML {@code type:} and runs the matching reader, renderer,
 * id-minter, and CedarValidator; instances are redirected to {@code instance_artifact_to_json}.
 */
final class SchemaArtifactToJsonToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void compiles_a_template_and_validates() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createTemplate("Demographics")));
    assertFalse(result.isError(), errorText(result));
    JsonNode json = jackson.readTree(textOf(result));
    assertEquals("https://schema.metadatacenter.org/core/Template", json.path("@type").asText(),
        "a template must render with the template @type; got:\n" + json.toPrettyString());
  }

  @Test void compiles_an_element() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createElement("Address")));
    assertFalse(result.isError(), errorText(result));
    assertEquals("https://schema.metadatacenter.org/core/TemplateElement",
        jackson.readTree(textOf(result)).path("@type").asText());
  }

  @Test void compiles_a_field() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createField("Patient name", "text-field")));
    assertFalse(result.isError(), errorText(result));
    assertEquals("https://schema.metadatacenter.org/core/TemplateField",
        jackson.readTree(textOf(result)).path("@type").asText());
  }

  @Test void mints_a_top_level_id_when_absent() throws Exception
  {
    // A hand-authored field YAML with no id: must come back with a minted template-fields IRI.
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "type: text-field\nname: Bare\n"));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("@id").asText().contains("/template-fields/"),
        "a field with no id must be minted a template-fields IRI; got:\n" + textOf(result));
  }

  @Test void redirects_an_instance() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact",
        "type: instance\nname: P1\nisBasedOn: https://repo.metadatacenter.org/templates/x\n"));
    assertTrue(result.isError(), "an instance is not a schema artifact");
    assertTrue(errorText(result).contains("instance_artifact_to_json"),
        "redirect should name instance_artifact_to_json; got: " + errorText(result));
  }

  @Test void rejects_missing_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("artifact"));
  }

  @Test void rejects_unparseable_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "{ not yaml or json"));
    assertTrue(result.isError(), "unparseable input must be a tool error");
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SchemaArtifactToJsonTool.handler(null,
        new McpSchema.CallToolRequest("schema_artifact_to_json", args));
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

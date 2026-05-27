package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SetIriFieldValueToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void sets_ror_field_value_with_label() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Org\n"
            + "description: Org template\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: ror\n"
            + "    type: ext-ror-field\n"
            + "    name: ROR\n");
    String instanceJson = createInstance(templateJson, "Stanford");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "ror",
        "iri", "https://ror.org/00f54p054",
        "label", "Stanford University"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    JsonNode rorField = rendered.path("ror");
    assertEquals("https://ror.org/00f54p054", rorField.path("@id").asText());
    assertEquals("Stanford University", rorField.path("rdfs:label").asText());
  }

  @Test void sets_link_field_value_without_label() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: T\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: homepage\n"
            + "    type: link-field\n"
            + "    name: Homepage\n");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "homepage",
        "iri", "https://example.com"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("https://example.com", rendered.path("homepage").path("@id").asText());
  }

  @Test void rejects_path_to_text_field()
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: T\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: note\n"
            + "    type: text-field\n"
            + "    name: Note\n");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "note",
        "iri", "https://x.example"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_field_value")
            || errorText(result).contains("set_controlled_term_field_value"),
        "error should redirect; got: " + errorText(result));
  }

  @Test void rejects_invalid_iri()
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: T\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: ror\n"
            + "    type: ext-ror-field\n"
            + "    name: ROR\n");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "ror",
        "iri", "not a uri with spaces"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("iri"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetIriFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("set_iri_field_value", args));
  }

  private static String compileTemplate(String yaml)
  {
    McpSchema.CallToolResult result = TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture template must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String createInstance(String templateJson, String name)
  {
    McpSchema.CallToolResult result = CreateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_instance", Map.of(
            "template_json", templateJson,
            "is_based_on", FAKE_BASED_ON,
            "name", name)));
    assertFalse(result.isError(),
        "fixture instance must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private ObjectNode parseJson(McpSchema.CallToolResult result) throws Exception
  {
    String text = textOf(result);
    JsonNode node = jackson.readTree(text);
    assertTrue(node.isObject(), "result must be a JSON object; got: " + text);
    return (ObjectNode) node;
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

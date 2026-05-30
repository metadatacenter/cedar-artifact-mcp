package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code set_iri_field_value}. Fixtures are built via the YAML-exchange tools
 * (create_template / create_field / add_field / create_instance); the tool returns the
 * updated instance as expanded YAML, asserted on via SnakeYAML. An IRI field instance
 * carries its URI under {@code children.<key>.id}, with an optional {@code label}.
 */
final class SetIriFieldValueToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  @Test void sets_ror_field_value_with_label()
  {
    String templateJson = templateWithField(createField("ROR", "ext-ror-field"), "ror");
    String instanceJson = createInstance(templateJson, "Stanford");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "ror",
        "iri", "https://ror.org/00f54p054",
        "label", "Stanford University"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> ror = child(parseYaml(result), "ror");
    assertEquals("https://ror.org/00f54p054", ror.get("id"), "ror id; got: " + ror);
    assertEquals("Stanford University", ror.get("label"), "ror label; got: " + ror);
  }

  @Test void sets_link_field_value_without_label()
  {
    String templateJson = templateWithField(createField("Homepage", "link-field"), "homepage");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "homepage",
        "iri", "https://example.com"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> homepage = child(parseYaml(result), "homepage");
    assertEquals("https://example.com", homepage.get("id"), "homepage id; got: " + homepage);
  }

  @Test void rejects_path_to_text_field()
  {
    String templateJson = templateWithField(createField("Note", "text-field"), "note");
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
    String templateJson = templateWithField(createField("ROR", "ext-ror-field"), "ror");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "ror",
        "iri", "not a uri with spaces"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("iri"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetIriFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("set_iri_field_value", args));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(io.modelcontextprotocol.server.McpSyncServerExchange e,
        McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture step '" + name + "' must succeed; got: " + errorText(result));
    return result;
  }

  private static String createTemplate(String name)
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", name)));
  }

  private static String createField(String name, String type)
  {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("name", name);
    args.put("type", type);
    return textOf(invokeTool(CreateFieldTool::handler, "create_field", args));
  }

  private static String templateWithField(String fieldYaml, String key)
  {
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent_json", createTemplate("Fixture"),
        "child_json", fieldYaml,
        "key", key)));
  }

  private static String createInstance(String templateJson, String name)
  {
    return textOf(invokeTool(CreateInstanceTool::handler, "create_instance", Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON,
        "name", name)));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result)
  {
    Object parsed = new Yaml().load(textOf(result));
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + textOf(result));
    Map<String, Object> map = (Map<String, Object>) parsed;
    assertEquals("instance", map.get("type"), "result must be an instance; got: " + textOf(result));
    return map;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> parent, String key)
  {
    Object children = parent.get("children");
    assertTrue(children instanceof Map, "expected a children map; got: " + children);
    Object node = ((Map<String, Object>) children).get(key);
    assertTrue(node instanceof Map, "child '" + key + "' must be a value-map; got: " + node);
    return (Map<String, Object>) node;
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

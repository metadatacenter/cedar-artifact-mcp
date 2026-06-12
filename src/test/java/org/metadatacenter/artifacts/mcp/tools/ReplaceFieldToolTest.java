package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code replace_field} — replaces the child at a key with a new field while
 * keeping the key's position in the parent's display order (where remove_child +
 * add_field would move it to the end).
 */
final class ReplaceFieldToolTest
{
  @Test void replaces_middle_child_keeping_its_position()
  {
    String parent = templateWithThreeFields();

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", parent,
        "child", createField("Patient Age", "numeric-field"),
        "key", "b"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> template = parseYaml(result, "template");
    assertEquals(List.of("a", "b", "c"), childKeys(template),
        "the replaced child must keep its position; got: " + textOf(result));
    Map<String, Object> replaced = childAt(template, "b");
    assertEquals("Patient Age", replaced.get("name"), "replacement content; got: " + replaced);
    assertEquals("numeric-field", replaced.get("type"), "replacement kind; got: " + replaced);
  }

  @Test void replaces_a_field_inside_an_element_parent()
  {
    String element = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", createField("Street", "text-field"),
        "key", "street")));

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", element,
        "child", createField("Street Name", "text-field"),
        "key", "street"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> updated = parseYaml(result, "element");
    assertEquals("Street Name", childAt(updated, "street").get("name"),
        "replacement must land at the key; got: " + textOf(result));
  }

  @Test void applies_per_site_overrides_to_the_replacement()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateWithThreeFields(),
        "child", createField("Patient Name", "text-field"),
        "key", "a",
        "isRequired", true,
        "isHidden", true));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> replaced = childAt(parseYaml(result, "template"), "a");
    Object configuration = replaced.get("configuration");
    assertTrue(configuration instanceof Map, "expected a configuration block; got: " + replaced);
    assertEquals(Boolean.TRUE, ((Map<?, ?>) configuration).get("required"),
        "isRequired override; got: " + configuration);
    assertEquals(Boolean.TRUE, ((Map<?, ?>) configuration).get("hidden"),
        "isHidden override; got: " + configuration);
  }

  @Test void rejects_an_unknown_key()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateWithThreeFields(),
        "child", createField("X", "text-field"),
        "key", "nope"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nope"), errorText(result));
  }

  @Test void rejects_a_missing_key()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateWithThreeFields(),
        "child", createField("X", "text-field")));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("key"), errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ReplaceFieldTool.handler(null,
        new McpSchema.CallToolRequest("replace_field", args));
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

  private static String createField(String name, String type)
  {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("name", name);
    args.put("type", type);
    return textOf(invokeTool(CreateFieldTool::handler, "create_field", args));
  }

  /** A template with text fields at keys a, b, c — in that display order. */
  private static String templateWithThreeFields()
  {
    String template = textOf(invokeTool(CreateTemplateTool::handler, "create_template",
        Map.of("name", "Fixture")));
    for (String key : List.of("a", "b", "c"))
      template = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
          "parent", template,
          "child", createField("Field " + key, "text-field"),
          "key", key)));
    return template;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result, String expectedType)
  {
    Object parsed = new Yaml().load(textOf(result));
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + textOf(result));
    Map<String, Object> map = (Map<String, Object>) parsed;
    assertEquals(expectedType, map.get("type"), "result kind; got: " + textOf(result));
    return map;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> children(Map<String, Object> parent)
  {
    Object children = parent.get("children");
    assertTrue(children instanceof List, "expected a children list; got: " + children);
    return (List<Map<String, Object>>) children;
  }

  private static List<String> childKeys(Map<String, Object> parent)
  {
    List<String> keys = new ArrayList<>();
    for (Map<String, Object> child : children(parent))
      keys.add(String.valueOf(child.get("key")));
    return keys;
  }

  private static Map<String, Object> childAt(Map<String, Object> parent, String key)
  {
    for (Map<String, Object> child : children(parent))
      if (key.equals(child.get("key")))
        return child;
    throw new AssertionError("no child at key '" + key + "' in " + parent);
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

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
 * Tests for {@code reorder_children} — sets the display order of a parent's children
 * from a complete permutation of the existing keys. Declarative: the same call with the
 * same keys is idempotent; a partial or padded list is an error (the library prunes
 * children absent from the order, so a partial list would delete, not merely unorder).
 */
final class ReorderChildrenToolTest
{
  @Test void reorders_template_children()
  {
    String template = templateWithChildren("a", "b", "c");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template, "keys", List.of("c", "a", "b")));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> reordered = parseYaml(result, "template");
    assertEquals(List.of("c", "a", "b"), childKeys(reordered),
        "children must render in the requested order; got: " + textOf(result));
    assertEquals("Field a", childAt(reordered, "a").get("name"),
        "reordering must not touch content; got: " + textOf(result));
  }

  @Test void reorders_element_children()
  {
    String element = textOf(invokeTool(CreateElementTool::handler, "create_element",
        Map.of("name", "Fixture")));
    for (String key : List.of("x", "y"))
      element = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
          "parent", element, "child", createField("Field " + key), "key", key)));

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", element, "keys", List.of("y", "x")));

    assertFalse(result.isError(), errorText(result));
    assertEquals(List.of("y", "x"), childKeys(parseYaml(result, "element")),
        "element children must render in the requested order; got: " + textOf(result));
  }

  @Test void is_idempotent_for_the_current_order()
  {
    String template = templateWithChildren("a", "b");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template, "keys", List.of("a", "b")));

    assertFalse(result.isError(), errorText(result));
    assertEquals(template, textOf(result),
        "restating the current order must be a no-op");
  }

  @Test void rejects_a_partial_list_echoing_the_current_order()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateWithChildren("a", "b", "c"), "keys", List.of("c", "a")));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("missing [b]"),
        "error should name the missing key; got: " + errorText(result));
    assertTrue(errorText(result).contains("[a, b, c]"),
        "error should echo the current order; got: " + errorText(result));
  }

  @Test void rejects_an_unknown_key()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateWithChildren("a", "b"), "keys", List.of("b", "a", "nope")));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("unknown keys [nope]"), errorText(result));
  }

  @Test void rejects_a_duplicate_key()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateWithChildren("a", "b"), "keys", List.of("a", "a")));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("duplicate key 'a'"), errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ReorderChildrenTool.handler(null,
        new McpSchema.CallToolRequest("reorder_children", args));
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

  private static String createField(String name)
  {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("name", name);
    args.put("type", "text-field");
    return textOf(invokeTool(CreateFieldTool::handler, "create_field", args));
  }

  /** A template with one text field per key, added in the given order. */
  private static String templateWithChildren(String... keys)
  {
    String template = textOf(invokeTool(CreateTemplateTool::handler, "create_template",
        Map.of("name", "Fixture")));
    for (String key : keys)
      template = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
          "parent", template, "child", createField("Field " + key), "key", key)));
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
  private static List<String> childKeys(Map<String, Object> parent)
  {
    Object children = parent.get("children");
    assertTrue(children instanceof List, "expected a children list; got: " + children);
    List<String> keys = new ArrayList<>();
    for (Map<String, Object> child : (List<Map<String, Object>>) children)
      keys.add(String.valueOf(child.get("key")));
    return keys;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> childAt(Map<String, Object> parent, String key)
  {
    for (Map<String, Object> child : (List<Map<String, Object>>) parent.get("children"))
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

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
 * The two halves of the instance-ordering contract:
 *
 * <ul>
 *   <li><strong>Order never affects validity</strong> — an instance whose children arrive in
 *       any order validates against its template;</li>
 *   <li><strong>Serialization is canonical</strong> — every instance a tool returns carries
 *       its children in the template's display order ({@code _ui.order}), whatever order the
 *       incoming instance had. The inflater enforces this, so it holds across all the
 *       instance-side tools.</li>
 * </ul>
 */
final class InstanceChildOrderTest
{
  @Test void a_scrambled_instance_still_validates()
  {
    String template = templateWithChildren("a", "b", "c");
    String instance = setValue(template, createInstance(template), "a", "va");
    instance = setValue(template, instance, "c", "vc");

    McpSchema.CallToolResult result = ValidateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance", Map.of(
            "template", template, "instance", reverseChildren(instance))));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : true"),
        "child order must not affect validity; got: " + textOf(result));
  }

  @Test void tools_serialize_children_in_template_order()
  {
    String template = templateWithChildren("a", "b", "c");
    String instance = setValue(template, createInstance(template), "a", "va");
    instance = setValue(template, instance, "c", "vc");

    // Feed a deliberately reversed instance through a value tool: the result must come
    // back in the template's order, values intact.
    String yaml = setValue(template, reverseChildren(instance), "b", "vb");

    assertEquals(List.of("a", "b", "c"), childKeys(yaml),
        "children must serialize in the template's display order; got: " + yaml);
    assertTrue(yaml.contains("va") && yaml.contains("vb") && yaml.contains("vc"),
        "reordering must not lose values; got: " + yaml);
  }

  @Test void instances_follow_a_reordered_template()
  {
    String template = templateWithChildren("a", "b", "c");
    String instance = setValue(template, createInstance(template), "a", "va");
    instance = setValue(template, instance, "b", "vb");
    instance = setValue(template, instance, "c", "vc");

    String reordered = textOf(invokeTool(ReorderChildrenTool::handler, "reorder_children", Map.of(
        "parent", template, "keys", List.of("c", "a", "b"))));
    String yaml = setValue(reordered, instance, "b", "vb2");

    assertEquals(List.of("c", "a", "b"), childKeys(yaml),
        "instances must follow the template's new display order; got: " + yaml);
  }

  @Test void nested_element_children_are_ordered_too()
  {
    String element = textOf(invokeTool(CreateElementTool::handler, "create_element",
        Map.of("name", "Pair")));
    for (String key : List.of("x", "y"))
      element = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
          "parent", element, "child", createField("Field " + key), "key", key)));
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate(), "child", element, "key", "pair")));

    String instance = setValue(template, createInstance(template), "pair/y", "vy");
    instance = setValue(template, instance, "pair/x", "vx");

    String yaml = setValue(template, instance, "pair/y", "vy2");
    Map<String, Object> pair = singleChild(parseYaml(yaml), "pair");
    assertEquals(List.of("x", "y"), new ArrayList<>(childrenMap(pair).keySet()),
        "nested children must follow the element's display order; got: " + yaml);
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

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

  private static String createTemplate()
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", "Fixture")));
  }

  private static String createField(String name)
  {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("name", name);
    args.put("type", "text-field");
    return textOf(invokeTool(CreateFieldTool::handler, "create_field", args));
  }

  private static String templateWithChildren(String... keys)
  {
    String template = createTemplate();
    for (String key : keys)
      template = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
          "parent", template, "child", createField("Field " + key), "key", key)));
    return template;
  }

  private static String createInstance(String template)
  {
    return textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template, "name", "Fixture instance")));
  }

  private static String setValue(String template, String instance, String path, String value)
  {
    return textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance, "field_path", path, "value", value)));
  }

  /** Re-serialize the instance YAML with its top-level children map reversed. */
  @SuppressWarnings("unchecked")
  private static String reverseChildren(String instanceYaml)
  {
    Map<String, Object> instance = parseYaml(instanceYaml);
    Map<String, Object> children = childrenMap(instance);
    List<String> keys = new ArrayList<>(children.keySet());
    LinkedHashMap<String, Object> reversed = new LinkedHashMap<>();
    for (int i = keys.size() - 1; i >= 0; i--)
      reversed.put(keys.get(i), children.get(keys.get(i)));
    ((Map<String, Object>) instance).put("children", reversed);
    return new Yaml().dump(instance);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(String yaml)
  {
    Object parsed = new Yaml().load(yaml);
    assertTrue(parsed instanceof Map, "expected a YAML mapping; got: " + yaml);
    return (Map<String, Object>) parsed;
  }

  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result)
  {
    return parseYaml(textOf(result));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> childrenMap(Map<String, Object> node)
  {
    Object children = node.get("children");
    assertTrue(children instanceof Map, "expected a children map; got: " + node);
    return (Map<String, Object>) children;
  }

  private static List<String> childKeys(String instanceYaml)
  {
    return new ArrayList<>(childrenMap(parseYaml(instanceYaml)).keySet());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> singleChild(Map<String, Object> instance, String key)
  {
    Object node = childrenMap(instance).get(key);
    assertTrue(node instanceof Map, "child '" + key + "' must be a map; got: " + node);
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

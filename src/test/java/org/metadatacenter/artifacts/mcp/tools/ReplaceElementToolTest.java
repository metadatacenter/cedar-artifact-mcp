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
 * Tests for {@code replace_element} — replaces the child at a key with a new element
 * while keeping the key's position in the parent's display order.
 */
final class ReplaceElementToolTest
{
  @Test void replaces_middle_element_keeping_its_position()
  {
    // Template with field a, element addr, field c — replace addr with a new element.
    String template = textOf(invokeTool(CreateTemplateTool::handler, "create_template",
        Map.of("name", "Fixture")));
    template = addField(template, "a");
    template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", template,
        "child", elementWithStreet("Address"),
        "key", "addr")));
    template = addField(template, "c");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "child", elementWithStreet("Postal Address"),
        "key", "addr"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> updated = parseYaml(result, "template");
    assertEquals(List.of("a", "addr", "c"), childKeys(updated),
        "the replaced child must keep its position; got: " + textOf(result));
    assertEquals("Postal Address", childAt(updated, "addr").get("name"),
        "replacement content; got: " + textOf(result));
  }

  @Test void applies_isMultiInstance_override_to_the_replacement()
  {
    String template = textOf(invokeTool(CreateTemplateTool::handler, "create_template",
        Map.of("name", "Fixture")));
    template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", template,
        "child", elementWithStreet("Address"),
        "key", "addr")));

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "child", elementWithStreet("Address"),
        "key", "addr",
        "isMultiInstance", true,
        "maxItems", 3));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> replaced = childAt(parseYaml(result, "template"), "addr");
    Object configuration = replaced.get("configuration");
    assertTrue(configuration instanceof Map, "expected a configuration block; got: " + replaced);
    assertEquals(Boolean.TRUE, ((Map<?, ?>) configuration).get("multiple"),
        "isMultiInstance override; got: " + configuration);
    assertEquals(3, ((Map<?, ?>) configuration).get("maxItems"),
        "maxItems override; got: " + configuration);
  }

  @Test void property_iri_maps_the_replacement_to_an_ontology_property()
  {
    String template = textOf(invokeTool(CreateTemplateTool::handler, "create_template",
        Map.of("name", "Fixture")));
    template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", template,
        "child", elementWithStreet("Address"),
        "key", "addr")));

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "child", elementWithStreet("Address"),
        "key", "addr",
        "property_iri", "https://schema.org/address"));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("propertyIri: https://schema.org/address"),
        "the property IRI must appear on the embedded replacement; got: " + textOf(result));
  }

  @Test void rejects_an_unknown_key()
  {
    String template = textOf(invokeTool(CreateTemplateTool::handler, "create_template",
        Map.of("name", "Fixture")));

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "child", elementWithStreet("Address"),
        "key", "nope"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nope"), errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ReplaceElementTool.handler(null,
        new McpSchema.CallToolRequest("replace_element", args));
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

  private static String addField(String parent, String key)
  {
    Map<String, Object> fieldArgs = new LinkedHashMap<>();
    fieldArgs.put("name", "Field " + key);
    fieldArgs.put("type", "text-field");
    String field = textOf(invokeTool(CreateFieldTool::handler, "create_field", fieldArgs));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", parent, "child", field, "key", key)));
  }

  /** An element with the given name carrying one text field, Street. */
  private static String elementWithStreet(String name)
  {
    String element = textOf(invokeTool(CreateElementTool::handler, "create_element",
        Map.of("name", name)));
    Map<String, Object> fieldArgs = new LinkedHashMap<>();
    fieldArgs.put("name", "Street");
    fieldArgs.put("type", "text-field");
    String street = textOf(invokeTool(CreateFieldTool::handler, "create_field", fieldArgs));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", element, "child", street, "key", "street")));
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

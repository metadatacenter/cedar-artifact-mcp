package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code create_element_instance} + {@code set_element_instance} — the
 * instance-side compose pair that makes multi-instance elements fillable: create an
 * element instance from the element schema, graft it into the parent instance (append at
 * index == size), then fill its fields with the regular value tools.
 */
final class SetElementInstanceToolTest
{
  @Test void create_element_instance_returns_a_typed_skeleton_with_a_minted_id()
  {
    McpSchema.CallToolResult result = CreateElementInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_element_instance",
            Map.of("element", addressElement())));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> entry = parseYaml(result, "element-instance");
    assertEquals("Address", entry.get("name"), "name defaults to the element's name; got: " + entry);
    assertTrue(String.valueOf(entry.get("id"))
            .startsWith("https://repo.metadatacenter.org/template-element-instances/"),
        "id must be minted in the element-instances collection; got: " + entry);
  }

  @Test void create_element_instance_honours_explicit_identity()
  {
    McpSchema.CallToolResult result = CreateElementInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_element_instance", Map.of(
            "element", addressElement(),
            "name", "Home address",
            "description", "Where the patient lives",
            "id", "https://repo.metadatacenter.org/template-element-instances/11112222-3333-4444-5555-666677778888")));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> entry = parseYaml(result, "element-instance");
    assertEquals("Home address", entry.get("name"));
    assertEquals("Where the patient lives", entry.get("description"));
    assertEquals("https://repo.metadatacenter.org/template-element-instances/11112222-3333-4444-5555-666677778888",
        entry.get("id"));
  }

  @Test void appends_entries_and_fills_them_end_to_end()
  {
    // The full workflow the pair exists for: a repeated element starts as an empty list;
    // appending element instances at index == size creates the entries, and the regular value
    // tools then address fields inside them.
    String template = multiAddressTemplate();
    String instance = createInstance(template);
    String entry = createEntry();

    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]", "element_instance", entry)));
    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[1]", "element_instance", entry)));

    instance = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]/street", "value", "First St")));
    String yaml = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[1]/street", "value", "Second St")));

    assertTrue(yaml.contains("First St") && yaml.contains("Second St"),
        "both appended entries must be fillable; got: " + yaml);
  }

  @Test void replaces_an_existing_entry_at_an_index()
  {
    String template = multiAddressTemplate();
    String instance = createInstance(template);
    String entry = createEntry();

    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]", "element_instance", entry)));
    instance = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]/street", "value", "Old St")));

    String yaml = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]", "element_instance", entry)));

    assertFalse(yaml.contains("Old St"),
        "replacing an entry must discard its old values; got: " + yaml);
  }

  @Test void replaces_a_single_instance_element()
  {
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate(),
        "child", addressElement(),
        "key", "address")));
    String instance = createInstance(template);
    instance = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "address/street", "value", "Old St")));

    String yaml = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "address", "element_instance", createEntry())));

    assertFalse(yaml.contains("Old St"),
        "the fresh element instance must replace the old one; got: " + yaml);
  }

  @Test void grafts_at_a_nested_path_inside_a_single_instance_element()
  {
    // contact (single element) containing addresses (multi element): the walker must
    // descend through the single-element step before applying the leaf.
    String contact = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Contact"))),
        "child", addressElement(),
        "key", "addresses",
        "isMultiInstance", true)));
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate(),
        "child", contact,
        "key", "contact")));
    String instance = createInstance(template);

    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "contact/addresses[0]", "element_instance", createEntry())));
    String yaml = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "contact/addresses[0]/street", "value", "Nested St")));

    assertTrue(yaml.contains("Nested St"),
        "the nested-path graft must be fillable; got: " + yaml);
  }

  @Test void grafts_below_an_indexed_multi_element_step()
  {
    // addresses (multi element) whose entries contain geo (single element): the walker's
    // indexed-intermediate descent must rebuild the right list entry.
    String geo = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Geo"))),
        "child", createField("Lat", "text-field"),
        "key", "lat")));
    String address = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", addressElement(),
        "child", geo,
        "key", "geo")));
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate(),
        "child", address,
        "key", "addresses",
        "isMultiInstance", true)));
    String instance = createInstance(template);

    // Append an address entry, fill its geo's lat, then replace just the geo element instance:
    // the lat value must disappear while the street stays untouched.
    String addressEntry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", address)));
    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]", "element_instance", addressEntry)));
    instance = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]/street", "value", "Kept St")));
    instance = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]/geo/lat", "value", "42.0")));

    String geoEntry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", geo)));
    String yaml = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]/geo", "element_instance", geoEntry)));

    assertFalse(yaml.contains("42.0"), "the replaced geo must lose its lat; got: " + yaml);
    assertTrue(yaml.contains("Kept St"), "the sibling street must survive; got: " + yaml);
  }

  @Test void appended_entries_can_be_deleted_with_unset()
  {
    // The full lifecycle across the new and existing tools: append two entries, fill one,
    // delete the other, and the survivor keeps its value.
    String template = multiAddressTemplate();
    String instance = createInstance(template);
    String entry = createEntry();

    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[0]", "element_instance", entry)));
    instance = textOf(invokeTool(SetElementInstanceTool::handler, "set_element_instance", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[1]", "element_instance", entry)));
    instance = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance,
        "field_path", "addresses[1]/street", "value", "Survivor St")));

    String yaml = textOf(invokeTool(UnsetFieldValueTool::handler, "unset_field_value", Map.of(
        "template", template, "instance", instance, "field_path", "addresses[0]")));

    assertTrue(yaml.contains("Survivor St"),
        "the remaining entry keeps its value; got: " + yaml);
    String refilled = textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", yaml,
        "field_path", "addresses[0]/street", "value", "Updated St")));
    assertTrue(refilled.contains("Updated St"),
        "the survivor must have shifted to index 0 after the delete; got: " + refilled);
  }

  @Test void rejects_an_index_gap()
  {
    McpSchema.CallToolResult result = SetElementInstanceTool.handler(null,
        new McpSchema.CallToolRequest("set_element_instance", Map.of(
            "template", multiAddressTemplate(), "instance", createInstance(multiAddressTemplate()),
            "field_path", "addresses[2]", "element_instance", createEntry())));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("out of range"), errorText(result));
  }

  @Test void rejects_a_multi_instance_path_without_an_index()
  {
    String template = multiAddressTemplate();
    McpSchema.CallToolResult result = SetElementInstanceTool.handler(null,
        new McpSchema.CallToolRequest("set_element_instance", Map.of(
            "template", template, "instance", createInstance(template),
            "field_path", "addresses", "element_instance", createEntry())));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("append"),
        "error should explain the index/append rule; got: " + errorText(result));
  }

  @Test void rejects_a_field_leaf()
  {
    String template = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createTemplate(),
        "child", createField("Name", "text-field"),
        "key", "name")));
    McpSchema.CallToolResult result = SetElementInstanceTool.handler(null,
        new McpSchema.CallToolRequest("set_element_instance", Map.of(
            "template", template, "instance", createInstance(template),
            "field_path", "name", "element_instance", createEntry())));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_*_field_value"),
        "error should redirect field values to the value tools; got: " + errorText(result));
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

  private static String createField(String name, String type)
  {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("name", name);
    args.put("type", type);
    return textOf(invokeTool(CreateFieldTool::handler, "create_field", args));
  }

  /** An element named Address carrying one text field, street. */
  private static String addressElement()
  {
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", createField("Street", "text-field"),
        "key", "street")));
  }

  /** A template whose addresses key is a multi-instance Address element. */
  private static String multiAddressTemplate()
  {
    return textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate(),
        "child", addressElement(),
        "key", "addresses",
        "isMultiInstance", true)));
  }

  private static String createInstance(String template)
  {
    return textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template, "name", "Fixture instance")));
  }

  private static String createEntry()
  {
    return textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", addressElement())));
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

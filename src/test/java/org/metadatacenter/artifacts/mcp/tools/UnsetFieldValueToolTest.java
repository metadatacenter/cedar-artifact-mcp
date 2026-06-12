package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TextFieldInstance;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code unset_field_value} — the inverse of the {@code set_*_field_value}
 * tools. The path decides the operation: single-instance field paths clear, indexed
 * multi-instance paths delete the entry, unindexed multi-instance paths clear the list.
 * Instances are sparse, so a cleared value simply disappears from the YAML.
 */
final class UnsetFieldValueToolTest
{
  @Test void clears_a_single_instance_field_and_is_idempotent()
  {
    // The field is marked required at the add site: unsetting must still succeed —
    // requiredValue is enforced by validate_instance, not mid-edit.
    String template = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createTemplate("Fixture"),
        "child", createField("Name", "text-field"),
        "key", "name",
        "isRequired", true)));
    String instance = setLiteral(template, createInstance(template), "name", "Alice");
    assertTrue(instance.contains("Alice"), "fixture must carry the value; got: " + instance);

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", instance, "field_path", "name"));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertFalse(yaml.contains("Alice"), "cleared value must disappear; got: " + yaml);

    McpSchema.CallToolResult again = invoke(Map.of(
        "template", template, "instance", yaml, "field_path", "name"));
    assertFalse(again.isError(), "unsetting an unset field must succeed; got: " + errorText(again));
  }

  @Test void clears_a_field_inside_an_element()
  {
    String street = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", createField("Street", "text-field"),
        "key", "street")));
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate("Fixture"),
        "child", street,
        "key", "address")));
    String instance = setLiteral(template, createInstance(template), "address/street", "Main St");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", instance, "field_path", "address/street"));

    assertFalse(result.isError(), errorText(result));
    assertFalse(textOf(result).contains("Main St"),
        "cleared nested value must disappear; got: " + textOf(result));
  }

  @Test void deletes_an_indexed_multi_instance_field_entry()
  {
    String template = multiEmailTemplate();
    String instance = createInstance(template);
    instance = setLiteral(template, instance, "emails[0]", "a@x.org");
    instance = setLiteral(template, instance, "emails[1]", "b@x.org");
    instance = setLiteral(template, instance, "emails[2]", "c@x.org");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", instance, "field_path", "emails[1]"));

    assertFalse(result.isError(), errorText(result));
    List<Map<String, Object>> emails = multiChild(parseYaml(result), "emails");
    assertEquals(2, emails.size(), "deleting one of three entries; got: " + textOf(result));
    assertEquals("a@x.org", emails.get(0).get("value"), "entry 0 keeps its place; got: " + emails);
    assertEquals("c@x.org", emails.get(1).get("value"), "later entries shift down; got: " + emails);
  }

  @Test void clears_a_whole_multi_instance_list()
  {
    String template = multiEmailTemplate();
    String instance = setLiteral(template, createInstance(template), "emails[0]", "a@x.org");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", instance, "field_path", "emails"));

    assertFalse(result.isError(), errorText(result));
    assertFalse(textOf(result).contains("a@x.org"),
        "cleared list must hold no values; got: " + textOf(result));
  }

  @Test void deletes_an_indexed_multi_instance_element_entry()
  {
    String street = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", createField("Street", "text-field"),
        "key", "street")));
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate("Fixture"),
        "child", street,
        "key", "addresses",
        "isMultiInstance", true)));

    // No tool authors multi-instance element entries yet (the entries normally arrive in
    // instances imported from JSON), so seed two sparse entries through the library model;
    // the tool's inflater completes them.
    TemplateInstanceArtifact base = readInstance(createInstance(template));
    TemplateInstanceArtifact seeded = TemplateInstanceArtifact.builder(base)
        .withMultiInstanceElementInstances("addresses", List.of(
            addressEntry("First St"), addressEntry("Second St")))
        .build();
    String instance = new JsonArtifactRenderer().renderTemplateInstanceArtifact(seeded).toString();

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", instance, "field_path", "addresses[0]"));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertFalse(yaml.contains("First St"), "deleted sub-record must disappear; got: " + yaml);
    assertTrue(yaml.contains("Second St"), "remaining sub-record must survive; got: " + yaml);
  }

  @Test void rejects_an_out_of_range_delete_index()
  {
    String template = multiEmailTemplate();
    String instance = setLiteral(template, createInstance(template), "emails[0]", "a@x.org");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", instance, "field_path", "emails[5]"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("out of range"), errorText(result));
  }

  @Test void rejects_an_unknown_path()
  {
    String template = multiEmailTemplate();
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", createInstance(template), "field_path", "nope"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nope"), errorText(result));
  }

  @Test void rejects_a_single_instance_element_leaf()
  {
    String street = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", createField("Street", "text-field"),
        "key", "street")));
    String template = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate("Fixture"),
        "child", street,
        "key", "address")));

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", template, "instance", createInstance(template), "field_path", "address"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("individually"),
        "error should direct at per-field unsetting; got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return UnsetFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("unset_field_value", args));
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

  private static String createInstance(String template)
  {
    return textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance", Map.of(
        "template", template, "name", "Fixture instance")));
  }

  private static String setLiteral(String template, String instance, String path, String value)
  {
    return textOf(invokeTool(SetLiteralFieldValueTool::handler, "set_literal_field_value", Map.of(
        "template", template, "instance", instance, "field_path", path, "value", value)));
  }

  /** A template with one multi-instance text field at key {@code emails}. */
  private static String multiEmailTemplate()
  {
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createTemplate("Fixture"),
        "child", createField("Email", "text-field"),
        "key", "emails",
        "isMultiInstance", true)));
  }

  /** A sparse address sub-record carrying just a street value. */
  private static ElementInstanceArtifact addressEntry(String street)
  {
    return ElementInstanceArtifact.builder()
        .withSingleInstanceFieldInstance("street",
            TextFieldInstance.builder().withValue(street).build())
        .build();
  }

  @SuppressWarnings("unchecked")
  private static TemplateInstanceArtifact readInstance(String yaml)
  {
    Object parsed = new Yaml().load(yaml);
    assertTrue(parsed instanceof Map, "instance must be a YAML mapping; got: " + yaml);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>((Map<String, Object>) parsed);
    return new YamlArtifactReader(true).readTemplateInstanceArtifact(map);
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
  private static List<Map<String, Object>> multiChild(Map<String, Object> parent, String key)
  {
    Object children = parent.get("children");
    assertTrue(children instanceof Map, "expected a children map; got: " + children);
    Object node = ((Map<String, Object>) children).get(key);
    assertTrue(node instanceof List, "child '" + key + "' must be a list; got: " + node);
    return (List<Map<String, Object>>) node;
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

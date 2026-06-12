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
 * Tests for {@code set_controlled_term_field_value}. The controlled-term schema field is
 * built by creating a controlled-term-field and layering a class constraint onto it via
 * {@code set_class_constraint} — that's what makes the library classify it as a
 * ControlledTermField. All fixtures move as YAML; the tool returns the updated instance
 * as expanded YAML, where a controlled-term value carries {@code id} + {@code label} +
 * {@code prefLabel} under its children entry.
 */
final class SetControlledTermFieldValueToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  @Test void sets_controlled_term_value()
  {
    String templateJson = controlledTermTemplate("diagnosis");
    String instanceJson = createInstance(templateJson, "Patient 42");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "diagnosis",
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer",
        "pref_label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> diag = child(parseYaml(result), "diagnosis");
    assertEquals("http://purl.obolibrary.org/obo/DOID_1612", diag.get("id"), "diagnosis id; got: " + diag);
    assertEquals("breast cancer", diag.get("label"), "diagnosis label; got: " + diag);
    assertEquals("breast cancer", diag.get("prefLabel"), "diagnosis prefLabel; got: " + diag);
  }

  @Test void pref_label_defaults_to_label_when_omitted()
  {
    String templateJson = controlledTermTemplate("diagnosis");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "diagnosis",
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> diag = child(parseYaml(result), "diagnosis");
    assertEquals("breast cancer", diag.get("prefLabel"),
        "prefLabel must default to label; got: " + diag);
  }

  @Test void rejects_path_to_non_controlled_term_field()
  {
    // A plain text-field schema (no controlled-term constraint) won't be classified as
    // ControlledTermField — the wire collision documented in memory. The setter must
    // refuse it cleanly and point at set_*_constraint.
    String templateJson = templateWithField(createField("Note", "text-field"), "note");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "note",
        "iri", "https://example.org/x",
        "label", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("controlled-term")
            && errorText(result).contains("set_class_constraint"),
        "error should mention controlled-term and set_*_constraint guidance; got: "
            + errorText(result));
  }

  @Test void rejects_missing_label()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", "{}",
        "instance", "{}",
        "field_path", "x",
        "iri", "https://x.example"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("label"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetControlledTermFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("set_controlled_term_field_value", args));
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
        "parent", createTemplate("Fixture"),
        "child", fieldYaml,
        "key", key)));
  }

  /**
   * Build a template carrying a controlled-term field at {@code key}: create the field,
   * layer a class constraint onto it (so the library classifies it as ControlledTermField),
   * then graft it onto a fresh template.
   */
  private static String controlledTermTemplate(String key)
  {
    String field = createField("Diagnosis", "controlled-term-field");
    String constrained = textOf(invokeTool(SetClassConstraintTool::handler, "set_class_constraint", Map.of(
        "field", field,
        "class_iri", "http://purl.obolibrary.org/obo/DOID_4",
        "ontology_acronym", "DOID",
        "label", "disease",
        "pref_label", "disease")));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createTemplate("Diagnosis template"),
        "child", constrained,
        "key", key)));
  }

  private static String createInstance(String templateJson, String name)
  {
    return textOf(invokeTool(CreateInstanceTool::handler, "create_instance", Map.of(
        "template", templateJson,
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

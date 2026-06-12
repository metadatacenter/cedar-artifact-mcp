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
 * Controlled-term values carry {@code id} + {@code label} only — no {@code prefLabel} —
 * matching what the CEDAR editor writes.
 */
final class SetIriFieldValueToolTest
{
  @Test void sets_ror_field_value_with_label()
  {
    String templateJson = templateWithField(createField("ROR", "ext-ror-field"), "ror");
    String instanceJson = createInstance(templateJson, "Stanford");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
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
        "template", templateJson,
        "instance", instanceJson,
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
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "note",
        "iri", "https://x.example"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_literal_field_value"),
        "error should redirect; got: " + errorText(result));
    assertTrue(errorText(result).contains("set_class_constraint"),
        "error should mention the constraint route for controlled-term intent; got: "
            + errorText(result));
  }

  @Test void rejects_invalid_iri()
  {
    String templateJson = templateWithField(createField("ROR", "ext-ror-field"), "ror");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "ror",
        "iri", "not a uri with spaces"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("iri"));
  }

  @Test void sets_controlled_term_value()
  {
    String templateJson = controlledTermTemplate("diagnosis");
    String instanceJson = createInstance(templateJson, "Patient 42");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "diagnosis",
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> diag = child(parseYaml(result), "diagnosis");
    assertEquals("http://purl.obolibrary.org/obo/DOID_1612", diag.get("id"), "diagnosis id; got: " + diag);
    assertEquals("breast cancer", diag.get("label"), "diagnosis label; got: " + diag);
    assertFalse(diag.containsKey("prefLabel"),
        "values carry @id + rdfs:label only, the shape the CEDAR editor writes; got: " + diag);
  }

  @Test void rejects_controlled_term_value_without_label()
  {
    String templateJson = controlledTermTemplate("diagnosis");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "diagnosis",
        "iri", "http://purl.obolibrary.org/obo/DOID_1612"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("label"),
        "error should demand the label; got: " + errorText(result));
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

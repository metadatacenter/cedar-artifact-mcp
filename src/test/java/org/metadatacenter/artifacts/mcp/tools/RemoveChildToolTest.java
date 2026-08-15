package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code remove_child} tool. Fixtures are composed with the other
 * threading tools (which now return expanded YAML), and the tool returns the updated
 * parent as expanded YAML.
 */
final class RemoveChildToolTest
{
  @Test void removes_field_from_template()
  {
    String template = createTemplate("Patient");
    template = addField(template, createField("Patient name", "text-field"), "patient_name");
    template = addField(template, createField("Age", "numeric-field"), "age");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "key", "patient_name"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    assertNull(childByKey(rendered, "patient_name"),
        "removed field must not appear in children; got: " + rendered.get("children"));
    assertNotNull(childByKey(rendered, "age"),
        "sibling field must still be present");

    assertTemplateValidates(textOf(result));
  }

  @Test void removes_element_from_template()
  {
    String template = createTemplate("With address");
    String address = addField(createElement("Address"), createField("Street", "text-field"), "street");
    template = addElement(template, address, "address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    assertNull(childByKey(rendered, "address"),
        "removed element must not appear in children");

    assertTemplateValidates(textOf(result));
  }

  @Test void removes_field_from_element_parent()
  {
    // Build an element with a field, then remove the field.
    String element = addField(createElement("Address"), createField("Street", "text-field"), "street");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", element,
        "key", "street"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);
    assertNull(childByKey(rendered, "street"),
        "removed field must not appear in the element's children");

    assertElementValidates(textOf(result));
  }

  @Test void rejects_unknown_key()
  {
    String template = createTemplate("T");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", template,
        "key", "nonexistent"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nonexistent"));
  }

  @Test void rejects_parent_without_at_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", "{}",
        "key", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"));
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("parent"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return RemoveChildTool.handler(null,
        new McpSchema.CallToolRequest("remove_child", args));
  }

  private static String createTemplate(String name)
  {
    McpSchema.CallToolResult result = CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String createElement(String name)
  {
    McpSchema.CallToolResult result = CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture element must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String createField(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "fixture field must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String addField(String parentYaml, String fieldYaml, String key)
  {
    McpSchema.CallToolResult result = AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", Map.of(
            "parent", parentYaml,
            "child", fieldYaml,
            "key", key)));
    assertFalse(result.isError(),
        "fixture add_field must succeed; got: " + errorText(result));
    return textOf(result);
  }

  private static String addElement(String parentYaml, String elementYaml, String key)
  {
    McpSchema.CallToolResult result = AddElementTool.handler(null,
        new McpSchema.CallToolRequest("add_element", Map.of(
            "parent", parentYaml,
            "child", elementYaml,
            "key", key)));
    assertFalse(result.isError(),
        "fixture add_element must succeed; got: " + errorText(result));
    return textOf(result);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result)
  {
    String text = textOf(result);
    Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + text);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    ((Map<Object, Object>) parsed).forEach((k, v) -> map.put(String.valueOf(k), v));
    return map;
  }

  /** Finds the entry in the parent's {@code children} list whose {@code key} equals {@code key}. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> childByKey(Map<String, Object> parent, String key)
  {
    Object children = parent.get("children");
    if (!(children instanceof List)) return null;
    for (Object entry : (List<Object>) children) {
      if (entry instanceof Map<?, ?> m && key.equals(String.valueOf(m.get("key")))) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        ((Map<Object, Object>) m).forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
      }
    }
    return null;
  }

  private static void assertTemplateValidates(String yaml)
  {
    var model = ArtifactExchange.readTemplateSchemaYaml(toReaderMap(yaml));
    var json = new JsonArtifactRenderer().renderTemplateSchemaArtifact(model);
    ValidationReport report;
    try {
      report = new CedarValidator().validateTemplate(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assertEquals("true", report.getValidationStatus(),
        "updated template must pass validateTemplate");
  }

  private static void assertElementValidates(String yaml)
  {
    var model = ArtifactExchange.readElementSchemaYaml(toReaderMap(yaml));
    var json = new JsonArtifactRenderer().renderElementSchemaArtifact(model);
    ValidationReport report;
    try {
      report = new CedarValidator().validateTemplateElement(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assertEquals("true", report.getValidationStatus(),
        "updated element must pass validateTemplateElement");
  }

  @SuppressWarnings("unchecked")
  private static LinkedHashMap<String, Object> toReaderMap(String yaml)
  {
    Object parsed = new org.yaml.snakeyaml.Yaml().load(yaml);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    ((Map<Object, Object>) parsed).forEach((k, v) -> map.put(String.valueOf(k), v));
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

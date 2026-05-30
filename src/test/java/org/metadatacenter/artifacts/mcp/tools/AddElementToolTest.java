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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code add_element} tool. Inputs are produced by the existing
 * {@code create_*} tools (which now return expanded YAML), and the tool itself returns
 * the updated parent as expanded YAML.
 */
final class AddElementToolTest
{
  @Test void adds_element_to_template_parent()
  {
    String templateYaml = createTemplate("Demographics");
    String elementYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    Map<String, Object> child = childByKey(rendered, "address");
    assertNotNull(child,
        "address element must appear in the template's children; got: "
            + rendered.get("children"));

    assertTemplateValidates(textOf(result));
  }

  @Test void adds_element_to_element_parent_nested()
  {
    // Nested element-in-element is a valid composition shape (e.g. Person containing
    // Address). The tool must support it via the element parent branch.
    String outerYaml = createElement("Person");
    String innerYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", outerYaml,
        "child_json", innerYaml,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    assertNotNull(childByKey(rendered, "address"),
        "address must appear in Person's children");

    assertElementValidates(textOf(result));
  }

  @Test void name_override_appears_in_child_configuration()
  {
    String templateYaml = createTemplate("Demographics");
    String elementYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml,
        "key", "home_address",
        "name", "Home address"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    Map<String, Object> child = childByKey(rendered, "home_address");
    assertNotNull(child, "home_address child must be present; got: " + rendered.get("children"));
    Map<String, Object> configuration = asMap(child.get("configuration"));
    assertEquals("Home address", configuration.get("overrideLabel"),
        "name override must surface in the child's configuration.overrideLabel; got: " + child);
  }

  @Test void isMultiInstance_true_marks_child_multiple()
  {
    String templateYaml = createTemplate("Multi");
    String elementYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml,
        "key", "addresses",
        "isMultiInstance", true));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    Map<String, Object> child = childByKey(rendered, "addresses");
    assertNotNull(child, "addresses child must be present; got: " + rendered.get("children"));
    Map<String, Object> configuration = asMap(child.get("configuration"));
    assertEquals(Boolean.TRUE, configuration.get("multiple"),
        "multi-instance element must carry configuration.multiple=true; got: " + child);
  }

  @Test void isMultiInstance_default_false_leaves_child_single()
  {
    String templateYaml = createTemplate("Single");
    String elementYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    Map<String, Object> child = childByKey(rendered, "address");
    assertNotNull(child, "address child must be present");
    Map<String, Object> configuration = asMap(child.get("configuration"));
    assertFalse(configuration.containsKey("multiple"),
        "default (isMultiInstance unset) must not mark the child multiple; got: " + child);
  }

  @Test void description_override_appears_in_child_configuration()
  {
    String templateYaml = createTemplate("Demographics");
    String elementYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml,
        "key", "addr",
        "description", "Override description"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    Map<String, Object> child = childByKey(rendered, "addr");
    assertNotNull(child, "addr child must be present");
    Map<String, Object> configuration = asMap(child.get("configuration"));
    assertEquals("Override description", configuration.get("overrideDescription"),
        "description override must surface in the child's configuration.overrideDescription");
  }

  @Test void minItems_and_maxItems_apply_to_multi_instance_element()
  {
    String templateYaml = createTemplate("Bounded");
    String elementYaml = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml,
        "key", "addresses",
        "isMultiInstance", true,
        "minItems", 1,
        "maxItems", 3));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    Map<String, Object> child = childByKey(rendered, "addresses");
    assertNotNull(child, "addresses child must be present");
    Map<String, Object> configuration = asMap(child.get("configuration"));
    assertEquals(Boolean.TRUE, configuration.get("multiple"),
        "multi-instance element must carry configuration.multiple=true");
    assertEquals(1, configuration.get("minItems"));
    assertEquals(3, configuration.get("maxItems"));
  }

  @Test void rejects_non_integer_minItems()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", createTemplate("X"),
        "child_json", createElement("X"),
        "key", "x",
        "minItems", "two"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("minItems"));
  }

  @Test void rejects_non_boolean_isMultiInstance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", createTemplate("X"),
        "child_json", createElement("X"),
        "key", "x",
        "isMultiInstance", "yes"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("isMultiInstance"));
  }

  @Test void key_defaults_to_childs_schema_name()
  {
    String templateYaml = createTemplate("Demographics");
    String elementYaml = createElement("address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> rendered = parseYaml(result);

    assertNotNull(childByKey(rendered, "address"),
        "element should appear under the default key (child's schema:name); got: "
            + rendered.get("children"));
  }

  @Test void rejects_duplicate_default_key()
  {
    String templateYaml = createTemplate("Dup");
    String elementYaml = createElement("address");

    McpSchema.CallToolResult first = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", elementYaml));
    assertFalse(first.isError(), errorText(first));

    McpSchema.CallToolResult second = invoke(Map.of(
        "parent_json", textOf(first),
        "child_json", elementYaml));
    assertTrue(second.isError(),
        "duplicate key (default) must produce isError=true; got: " + second);
    assertTrue(errorText(second).toLowerCase().contains("address"),
        "error should mention the conflicting key; got: " + errorText(second));
  }

  @Test void rejects_child_json_that_is_not_an_element()
  {
    String templateYaml = createTemplate("X");
    String fieldYaml = createFieldYaml("standalone", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateYaml,
        "child_json", fieldYaml,
        "key", "x"));
    assertTrue(result.isError(),
        "a field must not be accepted as a child element; got: " + result);
  }

  @Test void rejects_parent_without_at_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", "{}",
        "child_json", createElement("X"),
        "key", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"));
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("parent_json"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return AddElementTool.handler(null,
        new McpSchema.CallToolRequest("add_element", arguments));
  }

  private String createTemplate(String name)
  {
    McpSchema.CallToolResult result = CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private String createElement(String name)
  {
    McpSchema.CallToolResult result = CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture element must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private String createFieldYaml(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "fixture field must build cleanly; got: " + errorText(result));
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
      if (entry instanceof Map<?, ?> m && key.equals(String.valueOf(m.get("key"))))
        return asMap(m);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object raw)
  {
    if (!(raw instanceof Map)) return Map.of();
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    ((Map<Object, Object>) raw).forEach((k, v) -> map.put(String.valueOf(k), v));
    return map;
  }

  private static void assertTemplateValidates(String yaml)
  {
    var model = new YamlArtifactReader(true).readTemplateSchemaArtifact(toReaderMap(yaml));
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
    var model = new YamlArtifactReader(true).readElementSchemaArtifact(toReaderMap(yaml));
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
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

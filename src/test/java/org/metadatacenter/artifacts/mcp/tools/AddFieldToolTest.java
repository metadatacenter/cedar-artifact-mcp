package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code add_field} tool. Parallels {@link AddElementToolTest}: both
 * tools take a pre-built child (as YAML — the exchange form), infer the parent kind from
 * the artifact, and return the updated parent as expanded YAML revalidated by
 * CedarValidator.
 *
 * <p>In expanded YAML a parent's children are a {@code children} list (each entry a map
 * carrying its own {@code key}), not a {@code properties} map. Per-add-site overrides
 * (multiple / minItems / maxItems, label / description) surface in the child entry's
 * {@code configuration} block.
 */
final class AddFieldToolTest
{
  private ModelValidator cedarValidator;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
  }

  @Test void adds_field_to_template_parent() throws Exception
  {
    String templateYaml = createTemplate("Demographics");
    String fieldYaml = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml,
        "key", "patient_name"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    assertNotNull(childWithKey(yaml, "patient_name"),
        "field must appear in the parent's children list under its key; got: " + yaml.get("children"));

    assertEquals("true", cedarValidator.validateTemplate(renderTemplateJson(yaml)).getValidationStatus(),
        "updated template must pass validateTemplate");
  }

  @Test void adds_field_to_element_parent() throws Exception
  {
    String elementYaml = createElement("Address");
    String fieldYaml = createField("Country", "controlled-term-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", elementYaml,
        "child", fieldYaml,
        "key", "country"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    assertNotNull(childWithKey(yaml, "country"),
        "country must appear in the element's children list");

    assertEquals("true", cedarValidator.validateTemplateElement(renderElementJson(yaml)).getValidationStatus(),
        "updated element must pass validateTemplateElement");
  }

  @Test void name_override_appears_in_child_configuration() throws Exception
  {
    String templateYaml = createTemplate("Demographics");
    String fieldYaml = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml,
        "key", "patient_full_name",
        "name", "Patient full name"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    Map<String, Object> child = childWithKey(yaml, "patient_full_name");
    assertNotNull(child, "child must appear under its key; got: " + yaml.get("children"));
    assertEquals("Patient full name", configuration(child).get("overrideLabel"),
        "name override must surface in the child's configuration.overrideLabel; got: " + child);
  }

  @Test void isMultiInstance_true_marks_child_multiple() throws Exception
  {
    // CEDAR renders multi-instance fields as a JSON Schema array of objects; in expanded
    // YAML this surfaces as configuration.multiple = true on the child entry. The
    // isMultiInstance flag is the per-add-site control over which shape the parent gets.
    String templateYaml = createTemplate("Multi");
    String fieldYaml = createField("Email", "email-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml,
        "key", "emails",
        "isMultiInstance", true));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    Map<String, Object> child = childWithKey(yaml, "emails");
    assertNotNull(child, "child must appear under its key; got: " + yaml.get("children"));
    assertEquals(Boolean.TRUE, configuration(child).get("multiple"),
        "multi-instance field must carry configuration.multiple = true; got: " + child);
  }

  @Test void isMultiInstance_default_false_leaves_child_single() throws Exception
  {
    String templateYaml = createTemplate("Single");
    String fieldYaml = createField("Email", "email-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml,
        "key", "email"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    Map<String, Object> child = childWithKey(yaml, "email");
    assertNotNull(child, "child must appear under its key; got: " + yaml.get("children"));
    // Single-instance is the absence of a multiple flag (expanded YAML omits multiple: false).
    assertFalse(Boolean.TRUE.equals(configuration(child).get("multiple")),
        "default (isMultiInstance unset) must not mark the child multiple; got: " + child);
  }

  @Test void description_override_appears_in_child_configuration() throws Exception
  {
    String templateYaml = createTemplate("Demographics");
    String fieldYaml = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml,
        "key", "patient_name",
        "description", "Override description"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    Map<String, Object> child = childWithKey(yaml, "patient_name");
    assertNotNull(child, "child must appear under its key; got: " + yaml.get("children"));
    assertEquals("Override description", configuration(child).get("overrideDescription"),
        "description override must surface in the child's configuration.overrideDescription; got: " + child);
  }

  @Test void minItems_and_maxItems_apply_to_multi_instance_field() throws Exception
  {
    String templateYaml = createTemplate("Bounded");
    String fieldYaml = createField("Tag", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml,
        "key", "tags",
        "isMultiInstance", true,
        "minItems", 1,
        "maxItems", 5));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    Map<String, Object> child = childWithKey(yaml, "tags");
    assertNotNull(child, "child must appear under its key; got: " + yaml.get("children"));
    Map<String, Object> config = configuration(child);
    assertEquals(Boolean.TRUE, config.get("multiple"),
        "multi-instance field must carry configuration.multiple = true; got: " + child);
    assertEquals(1, asInt(config.get("minItems")),
        "minItems must surface in the child configuration; got: " + child);
    assertEquals(5, asInt(config.get("maxItems")),
        "maxItems must surface in the child configuration; got: " + child);
  }

  @Test void rejects_non_integer_minItems()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", createTemplate("X"),
        "child", createField("X", "text-field"),
        "key", "x",
        "minItems", "two"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("minItems"));
  }

  @Test void rejects_non_boolean_isMultiInstance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", createTemplate("X"),
        "child", createField("X", "text-field"),
        "key", "x",
        "isMultiInstance", "yes"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("isMultiInstance"));
  }

  @Test void key_defaults_to_childs_schema_name() throws Exception
  {
    String templateYaml = createTemplate("Demographics");
    String fieldYaml = createField("patient_email", "email-field");

    // No 'key' arg — should fall back to child's name ("patient_email").
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    assertNotNull(childWithKey(yaml, "patient_email"),
        "field should appear under the default key (child's name); got: " + yaml.get("children"));
  }

  @Test void rejects_duplicate_default_key() throws Exception
  {
    // Adding the same-named child twice with no explicit key surfaces the library's
    // duplicate-child guard — the second add must fail rather than silently overwriting.
    String templateYaml = createTemplate("Dup");
    String fieldYaml = createField("contact", "text-field");

    McpSchema.CallToolResult first = invoke(Map.of(
        "parent", templateYaml,
        "child", fieldYaml));
    assertFalse(first.isError(), errorText(first));

    McpSchema.CallToolResult second = invoke(Map.of(
        "parent", textOf(first),
        "child", fieldYaml));
    assertTrue(second.isError(),
        "duplicate key (default) must produce isError=true; got: " + second);
    assertTrue(errorText(second).toLowerCase().contains("contact"),
        "error should mention the conflicting key; got: " + errorText(second));
  }

  @Test void rejects_child_that_is_not_a_field()
  {
    // An element must not be accepted as a field child — that's add_element's job.
    String templateYaml = createTemplate("X");
    String elementYaml = createElement("not-a-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", templateYaml,
        "child", elementYaml,
        "key", "x"));
    assertTrue(result.isError(),
        "an element must not be accepted as a field child; got: " + result);
  }

  @Test void rejects_parent_without_at_type()
  {
    String fieldYaml = createField("X", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", "{}",
        "child", fieldYaml,
        "key", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"));
  }

  @Test void rejects_parent_with_field_at_type()
  {
    // A bare field is a valid CEDAR artifact but isn't a parent — add_field must refuse it.
    String fieldYaml = createField("standalone", "text-field");
    String anotherFieldYaml = createField("another", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", fieldYaml,
        "child", anotherFieldYaml,
        "key", "x"));
    assertTrue(result.isError(),
        "field artifact must not be accepted as a parent; got: " + result);
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("parent"));
  }

  @Test void property_iri_maps_the_child_to_an_ontology_property()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", createTemplate("Demographics"),
        "child", createField("Patient name", "text-field"),
        "key", "name",
        "property_iri", "https://schema.org/name"));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("propertyIri: https://schema.org/name"),
        "the property IRI must appear on the embedded child; got: " + textOf(result));
  }

  @Test void rejects_malformed_property_iri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent", createTemplate("Demographics"),
        "child", createField("Patient name", "text-field"),
        "property_iri", "not a uri with spaces"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("property_iri"), errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", arguments));
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

  private String createField(String name, String type)
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
    for (Map.Entry<Object, Object> e : ((Map<Object, Object>) parsed).entrySet())
      map.put(String.valueOf(e.getKey()), e.getValue());
    return map;
  }

  /** Find the child entry in the parent's {@code children} list whose {@code key} matches. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> childWithKey(Map<String, Object> parent, String key)
  {
    Object children = parent.get("children");
    if (!(children instanceof List)) return null;
    for (Object entry : (List<Object>) children) {
      if (entry instanceof Map<?, ?> child && key.equals(String.valueOf(child.get("key"))))
        return (Map<String, Object>) child;
    }
    return null;
  }

  /** The child entry's {@code configuration} block, or an empty map when absent. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> configuration(Map<String, Object> child)
  {
    Object config = child.get("configuration");
    return config instanceof Map ? (Map<String, Object>) config : Map.of();
  }

  private static ObjectNode renderTemplateJson(Map<String, Object> yaml)
  {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(yaml);
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(
        ArtifactExchange.readTemplateSchemaYaml(map));
  }

  private static ObjectNode renderElementJson(Map<String, Object> yaml)
  {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(yaml);
    return new JsonArtifactRenderer().renderElementSchemaArtifact(
        ArtifactExchange.readElementSchemaYaml(map));
  }

  private static int asInt(Object value)
  {
    assertNotNull(value, "expected a numeric value, got null");
    return ((Number) value).intValue();
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
  @Test void isRequired_and_isHidden_apply_to_the_embedded_copy()
  {
    String parent = textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", "Flags"))));
    String child = textOf(CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("type", "text-field", "name", "P"))));

    McpSchema.CallToolResult result = AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field",
            Map.of("parent", parent, "child", child, "isRequired", true, "isHidden", true)));

    assertFalse(result.isError(), textOf(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("required: true"), "embedded copy must be required; got:\n" + yaml);
    assertTrue(yaml.contains("hidden: true"), "embedded copy must be hidden; got:\n" + yaml);
  }

  @Test void omitted_flags_preserve_what_the_child_carries()
  {
    String parent = textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", "Preserve"))));
    String child = "type: text-field\nname: Already Required\nconfiguration:\n  required: true\n";

    McpSchema.CallToolResult result = AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", Map.of("parent", parent, "child", child)));

    assertFalse(result.isError(), textOf(result));
    assertTrue(textOf(result).contains("required: true"),
        "an already-required child must stay required when the flag is omitted; got:\n" + textOf(result));
  }

  @Test void isRequired_false_clears_a_required_child()
  {
    String parent = textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", "Clear"))));
    String child = "type: text-field\nname: Was Required\nconfiguration:\n  required: true\n";

    McpSchema.CallToolResult result = AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field",
            Map.of("parent", parent, "child", child, "isRequired", false)));

    assertFalse(result.isError(), textOf(result));
    assertFalse(textOf(result).contains("required: true"),
        "isRequired: false must clear the embedded copy's flag; got:\n" + textOf(result));
  }

}

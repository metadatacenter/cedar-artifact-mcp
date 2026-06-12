package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code set_field_value}. The tool now exchanges artifacts as expanded YAML:
 * fixtures are built via {@code create_template} / {@code create_field} / {@code add_field}
 * / {@code create_instance} (all YAML), and the tool's output is the updated instance as
 * expanded YAML — parsed here with SnakeYAML and, where structural correctness matters,
 * re-read and revalidated against the template with {@link CedarValidator}.
 */
final class SetFieldValueToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  @Test void sets_text_field_value_at_top_level()
  {
    String templateJson = templateWithField(createField("Patient name", "text-field"), "patient_name");
    String instanceJson = createInstance(templateJson, "Patient 42");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "patient_name",
        "value", "Alice"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> child = child(parseYaml(result), "patient_name");
    assertEquals("Alice", child.get("value"),
        "patient_name's value must equal the supplied string; got: " + child);
  }

  @Test void sets_numeric_field_value()
  {
    String templateJson = templateWithField(createField("Age", "numeric-field"), "age");
    String instanceJson = createInstance(templateJson, "Patient");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "age",
        "value", 42));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> child = child(parseYaml(result), "age");
    assertEquals("42", String.valueOf(child.get("value")),
        "numeric value must be preserved; got: " + child);
  }

  @Test void numeric_set_preserves_xsd_type_so_instance_still_validates()
  {
    // The template's per-field sub-schema for numeric fields requires both @value and
    // @type. set_field_value rebuilds the FieldInstance — if it forgets to thread the
    // declared XsdNumericDatatype, @type vanishes and the instance fails CedarValidator
    // with "object has missing required properties (['@type'])".
    String fieldJson = createField("Age", "numeric-field", Map.of("datatype", "xsd:int"));
    String templateJson = templateWithField(fieldJson, "age");
    String instanceJson = createInstance(templateJson, "P1");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "age",
        "value", 30));

    assertFalse(result.isError(), errorText(result));
    String instanceYaml = textOf(result);
    Map<String, Object> child = child(parseYaml(instanceYaml), "age");
    assertEquals("30", String.valueOf(child.get("value")));
    assertEquals("xsd:int", child.get("datatype"),
        "datatype must survive set_field_value; rendered child: " + child);

    assertValidatesAgainst(instanceYaml, templateJson);
  }

  @Test void set_value_does_not_drop_other_fields()
  {
    // Setting one field's value re-renders the whole instance. If the renderer drops the
    // sibling untouched literal field, the template's sub-schema rejects the result with
    // "object has missing required properties (['@value'])". Validation against the
    // template is the load-bearing check (compact YAML elides value-less children from
    // the human view, so the sibling's absence in the YAML is expected).
    String templateJson = templateWithFields(
        List.of(createField("Patient name", "text-field"), createField("Notes", "text-field")),
        List.of("patient_name", "notes"));
    String instanceJson = createInstance(templateJson, "P1");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "patient_name",
        "value", "Alice"));

    assertFalse(result.isError(), errorText(result));
    String instanceYaml = textOf(result);
    assertEquals("Alice", child(parseYaml(instanceYaml), "patient_name").get("value"));
    assertValidatesAgainst(instanceYaml, templateJson);
  }

  @Test void sets_field_value_inside_nested_element()
  {
    String streetField = createField("Street", "text-field");
    String element = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createElement("Address"),
        "child", streetField,
        "key", "street")));
    String templateJson = textOf(invokeTool(AddElementTool::handler, "add_element", Map.of(
        "parent", createTemplate("With address"),
        "child", element,
        "key", "address")));
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "address/street",
        "value", "221B Baker St"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> address = child(parseYaml(result), "address");
    Map<String, Object> street = child(address, "street");
    assertEquals("221B Baker St", street.get("value"),
        "nested field value must be set; got address: " + address);
  }

  @Test void appends_value_to_multi_instance_field()
  {
    String templateJson = templateWithMultiField(createField("Tag", "text-field"), "tags");
    String instanceJson = createInstance(templateJson, "P");

    // First append: index 0 on an empty list = append "alpha".
    McpSchema.CallToolResult first = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "tags[0]",
        "value", "alpha"));
    assertFalse(first.isError(), errorText(first));

    // Second append: index 1 on a 1-length list = append "beta".
    McpSchema.CallToolResult second = invoke(Map.of(
        "template", templateJson,
        "instance", textOf(first),
        "field_path", "tags[1]",
        "value", "beta"));
    assertFalse(second.isError(), errorText(second));

    List<?> tags = childList(parseYaml(second), "tags");
    assertEquals(2, tags.size(), "tags should be a 2-element list; got: " + tags);
    assertEquals("alpha", ((Map<?, ?>) tags.get(0)).get("value"));
    assertEquals("beta", ((Map<?, ?>) tags.get(1)).get("value"));
  }

  @Test void replaces_existing_multi_instance_field_value()
  {
    String templateJson = templateWithMultiField(createField("Tag", "text-field"), "tags");
    String instanceJson = createInstance(templateJson, "P");

    // Append then replace at index 0.
    String afterAppend = textOf(invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "tags[0]",
        "value", "alpha")));
    McpSchema.CallToolResult replaced = invoke(Map.of(
        "template", templateJson,
        "instance", afterAppend,
        "field_path", "tags[0]",
        "value", "ALPHA"));

    assertFalse(replaced.isError(), errorText(replaced));
    List<?> tags = childList(parseYaml(replaced), "tags");
    assertEquals(1, tags.size(), "list length should still be 1 after replace");
    assertEquals("ALPHA", ((Map<?, ?>) tags.get(0)).get("value"));
  }

  @Test void rejects_multi_instance_field_index_out_of_range()
  {
    String templateJson = templateWithMultiField(createField("Tag", "text-field"), "tags");
    String instanceJson = createInstance(templateJson, "P");

    // Index 5 on an empty list is out of range (> size).
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "tags[5]",
        "value", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("out of range"),
        "error should mention out-of-range; got: " + errorText(result));
  }

  @Test void rejects_path_to_iri_field()
  {
    String templateJson = templateWithField(createField("ROR", "ext-ror-field"), "ror");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "ror",
        "value", "https://ror.org/x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_iri_field_value"),
        "error should redirect to set_iri_field_value; got: " + errorText(result));
  }

  @Test void rejects_unknown_field_path()
  {
    String templateJson = templateWithField(createField("A", "text-field"), "a");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson,
        "field_path", "nonexistent",
        "value", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nonexistent"));
  }

  @Test void rejects_missing_value()
  {
    String templateJson = createTemplate("P");
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", "{}",
        "field_path", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("value"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return SetFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("set_field_value", arguments));
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

  private static String createElement(String name)
  {
    return textOf(invokeTool(CreateElementTool::handler, "create_element", Map.of("name", name)));
  }

  private static String createField(String name, String type)
  {
    return createField(name, type, Map.of());
  }

  private static String createField(String name, String type, Map<String, Object> extra)
  {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("name", name);
    args.put("type", type);
    args.putAll(extra);
    return textOf(invokeTool(CreateFieldTool::handler, "create_field", args));
  }

  /** Build a single-field template: create_template + add_field(child, key). */
  private static String templateWithField(String fieldYaml, String key)
  {
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createTemplate("Fixture"),
        "child", fieldYaml,
        "key", key)));
  }

  /** Build a multi-instance single-field template. */
  private static String templateWithMultiField(String fieldYaml, String key)
  {
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", createTemplate("Fixture"),
        "child", fieldYaml,
        "key", key,
        "isMultiInstance", true)));
  }

  /** Build a template with several fields added in order. */
  private static String templateWithFields(List<String> fieldYamls, List<String> keys)
  {
    String parent = createTemplate("Fixture");
    for (int i = 0; i < fieldYamls.size(); i++)
      parent = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
          "parent", parent,
          "child", fieldYamls.get(i),
          "key", keys.get(i))));
    return parent;
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
    return parseYaml(textOf(result));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(String yamlText)
  {
    Object parsed = new Yaml().load(yamlText);
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + yamlText);
    Map<String, Object> map = (Map<String, Object>) parsed;
    assertEquals("instance", map.get("type"), "result must be an instance; got: " + yamlText);
    return map;
  }

  /** The value-map for a single-instance child under the instance's (or element's) children map. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> parent, String key)
  {
    Object children = parent.get("children");
    assertTrue(children instanceof Map, "expected a children map; got: " + children);
    Object node = ((Map<String, Object>) children).get(key);
    assertTrue(node instanceof Map, "child '" + key + "' must be a value-map; got: " + node);
    return (Map<String, Object>) node;
  }

  @SuppressWarnings("unchecked")
  private static List<?> childList(Map<String, Object> parent, String key)
  {
    Object children = parent.get("children");
    assertTrue(children instanceof Map, "expected a children map; got: " + children);
    Object node = ((Map<String, Object>) children).get(key);
    assertTrue(node instanceof List, "child '" + key + "' must be a list; got: " + node);
    return (List<?>) node;
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

  /**
   * Reads the template and instance YAML back to the model, renders both to JSON, and runs
   * CedarValidator.validateTemplateInstance — mirroring how {@code ValidateInstanceTool}
   * drives the validator (its contract is with the JSON Schema serialization).
   */
  private static void assertValidatesAgainst(String instanceYaml, String templateYaml)
  {
    YamlArtifactReader reader = new YamlArtifactReader(true);
    JsonArtifactRenderer renderer = new JsonArtifactRenderer();
    TemplateSchemaArtifact template = reader.readTemplateSchemaArtifact(yamlMap(templateYaml));
    TemplateInstanceArtifact instance = reader.readTemplateInstanceArtifact(yamlMap(instanceYaml));
    ObjectNode templateNode = renderer.renderTemplateSchemaArtifact(template);
    // The YAML instance is sparse; inflate against the template before validating, exactly as
    // validate_instance / instance_to_json do at the JSON boundary.
    ObjectNode instanceNode = renderer.renderTemplateInstanceArtifact(
        InstanceInflater.inflate(template, instance));
    ValidationReport report;
    try {
      report = new CedarValidator().validateTemplateInstance(instanceNode, templateNode);
    } catch (Exception e) {
      throw new AssertionError("CedarValidator threw while validating instance: " + e.getMessage(), e);
    }
    assertEquals("true", report.getValidationStatus(),
        "instance must validate against its template; errors: " + report.getErrors());
  }

  @SuppressWarnings("unchecked")
  private static LinkedHashMap<String, Object> yamlMap(String yamlText)
  {
    Object parsed = new Yaml().load(yamlText);
    assertTrue(parsed instanceof Map, "YAML must be a mapping; got: " + yamlText);
    return new LinkedHashMap<>((Map<String, Object>) parsed);
  }
}

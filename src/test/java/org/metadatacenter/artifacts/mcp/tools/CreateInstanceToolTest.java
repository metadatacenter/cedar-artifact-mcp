package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code create_instance} tool. The headline check is that the resulting
 * skeleton instance validates against its template via
 * {@code validate_instance} — proving the structural walk produced something
 * CedarValidator accepts.
 */
final class CreateInstanceToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void empty_template_yields_minimal_valid_instance() throws Exception
  {
    String templateJson = createTemplate("Demographics");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Demographics", rendered.path("schema:name").asText(),
        "default name should be the template's schema:name");
    assertEquals(FAKE_BASED_ON, rendered.path("schema:isBasedOn").asText());

    assertValidatesAgainst(rendered, templateJson);
  }

  @Test void template_with_text_field_child_seeds_empty_field_instance() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Patient\n"
            + "description: Patient template\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n"
            + "    description: Free-text patient name\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON,
        "name", "Patient 42"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Patient 42", rendered.path("schema:name").asText());
    JsonNode child = rendered.path("patient_name");
    assertTrue(child.isObject(),
        "patient_name must appear as a child object on the instance; got:\n" + rendered);

    assertValidatesAgainst(rendered, templateJson);
  }

  @Test void numeric_field_child_seeds_instance_with_xsd_type() throws Exception
  {
    // Numeric typed-literal instances must carry both @value and @type — the
    // per-field sub-schema lists both as required. A skeleton instance that
    // omits @type fails CedarValidator with
    // "object has missing required properties (['@type']), /Age".
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: PatientStudy\n"
            + "modelVersion: 1.6.0\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "children:\n"
            + "  - key: Age\n"
            + "    type: numeric-field\n"
            + "    name: Age\n"
            + "    datatype: xsd:int\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode age = rendered.path("Age");
    assertTrue(age.isObject(), "Age must appear as a child object; got:\n" + rendered);
    assertTrue(age.has("@type"),
        "Age sub-instance must carry @type (xsd:int) so the template's sub-schema "
            + "validates; got: " + age);
    assertEquals("xsd:int", age.path("@type").asText(),
        "Age @type must match the field's declared xsd:int datatype");

    assertValidatesAgainst(rendered, templateJson);
  }

  @Test void temporal_field_child_seeds_instance_with_xsd_type() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: WithTemporal\n"
            + "modelVersion: 1.6.0\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "children:\n"
            + "  - key: visit_date\n"
            + "    type: temporal-field\n"
            + "    name: Visit date\n"
            + "    datatype: xsd:date\n"
            + "    granularity: day\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode child = rendered.path("visit_date");
    assertTrue(child.has("@type"),
        "temporal sub-instance must carry @type matching the field's xsd:date; got: " + child);
    assertEquals("xsd:date", child.path("@type").asText());

    assertValidatesAgainst(rendered, templateJson);
  }

  @Test void multi_instance_field_seeds_empty_array() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Patient with tags\n"
            + "description: Multi-instance test\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: tags\n"
            + "    type: text-field\n"
            + "    name: Tag\n"
            + "    configuration:\n"
            + "      multiple: true\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode tags = rendered.path("tags");
    assertTrue(tags.isArray(),
        "multi-instance child must render as a JSON array; got: " + tags);
    assertEquals(0, tags.size(), "empty multi-instance child must be an empty array");
  }

  @Test void nested_element_is_recursively_populated() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: With address\n"
            + "description: Template with nested element\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: address\n"
            + "    type: element\n"
            + "    name: Address\n"
            + "    description: Postal address\n"
            + "    modelVersion: 1.6.0\n"
            + "    children:\n"
            + "      - key: street\n"
            + "        type: text-field\n"
            + "        name: Street\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode addr = rendered.path("address");
    assertTrue(addr.isObject(),
        "single-instance element child must render as an object; got: " + addr);
    assertTrue(addr.path("street").isObject(),
        "nested field instance must appear inside the element; got address:\n" + addr);

    assertValidatesAgainst(rendered, templateJson);
  }

  @Test void attribute_value_field_seeds_empty_group() throws Exception
  {
    // attribute-value fields live in a separate map (attributeValueFieldInstanceGroups)
    // on the parent instance — the walker must seed them with an empty inner map so
    // the LLM can later populate per-attribute fields without the group placeholder
    // being missing.
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: With av\n"
            + "description: Template with attribute-value field\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: extras\n"
            + "    type: attribute-value-field\n"
            + "    name: Extras\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    // The attribute-value group renders as an array of attribute-name strings under
    // the group's key. An empty group renders as an empty array.
    JsonNode extras = rendered.path("extras");
    assertTrue(extras.isArray(),
        "empty attribute-value group must render as a JSON array; got: " + extras);
    assertEquals(0, extras.size(),
        "freshly-seeded attribute-value group must be empty");
  }

  @Test void static_fields_are_skipped() throws Exception
  {
    // static-section-break is a UI marker with no instance representation; the
    // walker must skip it without erroring.
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: With static\n"
            + "description: Template with a static field\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: section\n"
            + "    type: static-section-break\n"
            + "    name: Section\n"
            + "  - key: note\n"
            + "    type: text-field\n"
            + "    name: Note\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "is_based_on", FAKE_BASED_ON));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("note").isObject(),
        "non-static field should be populated; got: " + rendered);
    assertTrue(rendered.path("section").isMissingNode(),
        "static field should be absent from the instance; got 'section': "
            + rendered.path("section"));
  }

  @Test void rejects_template_without_at_id_and_no_is_based_on()
  {
    // create_template returns a template with @id=null. Without an explicit
    // is_based_on argument, the instance has no canonical reference to "the template"
    // — surface that as a clean error rather than building a bogus instance.
    String templateJson = createTemplate("Unsaved");

    McpSchema.CallToolResult result = invoke(Map.of("template_json", templateJson));

    assertTrue(result.isError(),
        "missing is_based_on with @id-less template must produce isError=true");
    assertTrue(errorText(result).contains("is_based_on"),
        "error should mention is_based_on; got: " + errorText(result));
  }

  @Test void rejects_invalid_is_based_on_uri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", createTemplate("X"),
        "is_based_on", "not a uri with spaces"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("is_based_on"));
  }

  @Test void rejects_missing_template_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("template_json"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return CreateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_instance", arguments));
  }

  private static String createTemplate(String name)
  {
    McpSchema.CallToolResult result = CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String compileTemplate(String yaml)
  {
    McpSchema.CallToolResult result = TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture template YAML must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private void assertValidatesAgainst(JsonNode instanceJson, String templateJson) throws Exception
  {
    McpSchema.CallToolResult result = ValidateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance", Map.of(
            "template_json", templateJson,
            "instance_json", jackson.writeValueAsString(instanceJson))));
    assertFalse(result.isError(), errorText(result));
    JsonNode report = jackson.readTree(textOf(result));
    assertTrue(report.path("valid").asBoolean(),
        "skeleton instance must validate against its template; got report:\n"
            + report.toPrettyString());
  }

  private ObjectNode parseJson(McpSchema.CallToolResult result) throws Exception
  {
    String text = textOf(result);
    JsonNode node = jackson.readTree(text);
    assertTrue(node.isObject(), "result must be a JSON object; got: " + text);
    return (ObjectNode) node;
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

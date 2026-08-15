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
 * Tests for the {@code create_template_instance} tool. A created instance is <em>sparse</em>: unset
 * fields are omitted from the YAML (no `value: null`, no `{}`). The structural completeness the
 * template requires lives at the JSON boundary — {@code validate_instance_artifact} and
 * {@code render_instance_artifact} (format: json) inflate the instance against the template. So the headline check is
 * that the instance validates, and structural assertions are made against the inflated JSON
 * (see {@link #inflatedJson}).
 */
final class CreateTemplateInstanceToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void empty_template_yields_minimal_valid_instance() throws Exception
  {
    String templateJson = createTemplate("Demographics");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Demographics", rendered.path("schema:name").asText(),
        "default name should be the template's schema:name");
    assertEquals(templateId(templateJson), rendered.path("schema:isBasedOn").asText());

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
        "template", templateJson,
        "name", "Patient 42"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Patient 42", rendered.path("schema:name").asText());
    JsonNode child = inflatedJson(result, templateJson).path("patient_name");
    assertTrue(child.isObject(),
        "patient_name must appear as a child object on the inflated instance; got:\n" + rendered);

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
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = inflatedJson(result, templateJson);

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
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = inflatedJson(result, templateJson);

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
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = inflatedJson(result, templateJson);

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
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = inflatedJson(result, templateJson);

    JsonNode addr = rendered.path("address");
    assertTrue(addr.isObject(),
        "single-instance element child must render as an object; got: " + addr);
    assertTrue(addr.path("street").isObject(),
        "nested field instance must appear inside the element; got address:\n" + addr);

    // Not assertValidatesAgainst: a skeleton with a nested element does not validate against its
    // template, and the reason is in the artifact library rather than here. See
    // aNestedElementOccurrenceHasNoIdentifierItsTemplateWillAccept below.
    assertNestedElementIdentifierIsTheOnlyComplaint(rendered, templateJson);
  }

  /**
   * A finding, not an exemption: an element occurrence inside a freshly built instance carries no
   * identifier — a repository assigns that on save — and the artifact library renders it {@code null}.
   * A template types its own instances' {@code @id} as a URI or null, but types a nested element's as
   * a URI, so the template refuses the instance the same library builds from it.
   *
   * <p>Aligning the two would change the schema every stored CEDAR element carries, so it is a model
   * decision rather than a fix to make here. This asserts that the mismatch is the only thing wrong
   * with the skeleton; when the library settles it, this fails and should become
   * {@code assertValidatesAgainst}.
   */
  private void assertNestedElementIdentifierIsTheOnlyComplaint(JsonNode instanceJson, String templateJson)
      throws Exception
  {
    McpSchema.CallToolResult result = ValidateInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance_artifact", Map.of(
            "schema_artifact", templateJson,
            "instance_artifact", jackson.writeValueAsString(instanceJson))));
    assertFalse(result.isError(), errorText(result));

    JsonNode report = jackson.readTree(textOf(result));
    JsonNode errors = report.path("errors");
    assertEquals(1, errors.size(), "expected only the element identifier to be refused; got:\n"
        + report.toPrettyString());
    String only = errors.get(0).asText();
    assertTrue(only.contains("/address/@id") && only.contains("null found, string expected"),
        "expected the nested element identifier to be the complaint; got: " + only);
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
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = inflatedJson(result, templateJson);

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
        "template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = inflatedJson(result, templateJson);

    assertTrue(rendered.path("note").isObject(),
        "non-static field should be populated; got: " + rendered);
    assertTrue(rendered.path("section").isMissingNode(),
        "static field should be absent from the instance; got 'section': "
            + rendered.path("section"));
  }

  @Test void created_instance_is_sparse_with_no_empty_slots() throws Exception
  {
    // The user-facing guarantee: a freshly created instance carries no unset-field noise — no
    // `value: null`, no empty-mapping `{}`, and the unset fields are omitted from the YAML
    // entirely. It is still structurally complete once inflated against its template (validates).
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: PatientStudy\n"
            + "modelVersion: 1.6.0\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "children:\n"
            + "  - key: Patient Name\n    type: text-field\n    name: Patient Name\n"
            + "  - key: Age\n    type: numeric-field\n    name: Age\n    datatype: xsd:int\n"
            + "  - key: tags\n    type: text-field\n    name: Tag\n    configuration:\n      multiple: true\n");

    McpSchema.CallToolResult result = invoke(Map.of("template", templateJson));
    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);

    assertFalse(yaml.contains("value: null"),
        "a sparse instance must not carry a value: null placeholder; got:\n" + yaml);
    assertFalse(yaml.contains("{}"),
        "a sparse instance must not carry an empty-mapping {} slot; got:\n" + yaml);
    assertFalse(yaml.contains("[]"),
        "a sparse instance must not carry an empty-list [] slot; got:\n" + yaml);
    assertFalse(yaml.contains("Patient Name:"),
        "an unset field must be omitted from the sparse instance; got:\n" + yaml);
    assertFalse(yaml.contains("Age:"),
        "an unset field must be omitted from the sparse instance; got:\n" + yaml);

    // Sparse, but still complete once inflated at the JSON boundary.
    assertValidatesAgainst(parseJson(result), templateJson);
  }

  @Test void mints_instance_id_when_omitted() throws Exception
  {
    // The instance's own @id is auto-minted when absent (DESIGN.md Principle 10), distinct
    // from isBasedOn, which is derived from the template's @id.
    String templateJson = createTemplate("Demographics");
    McpSchema.CallToolResult result = invoke(Map.of("template", templateJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    MintedIds.assertMintedId(rendered.get("@id"), "template-instances");
    assertEquals(templateId(templateJson), rendered.path("schema:isBasedOn").asText(),
        "minting the instance @id must not disturb isBasedOn");
  }

  @Test void preserves_supplied_instance_id() throws Exception
  {
    String id = "https://repo.metadatacenter.org/template-instances/abc-123";
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", createTemplate("Demographics"),
        "id", id));

    assertFalse(result.isError(), errorText(result));
    assertEquals(id, parseJson(result).get("@id").asText(),
        "a supplied instance id must be preserved, not overwritten by minting");
  }

  @Test void rejects_relative_instance_id()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", createTemplate("Demographics"),
        "id", "template-instances/abc-123"));

    assertTrue(result.isError(), "a non-absolute instance id should produce an error result");
    assertTrue(errorText(result).toLowerCase().contains("absolute"),
        "error should explain the id must be absolute; got: " + errorText(result));
  }

  @Test void rejects_missing_template()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("template"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", arguments));
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
    McpSchema.CallToolResult result = RenderSchemaArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_schema_artifact", Map.of("schema_artifact", yaml, "format", "json")));
    assertFalse(result.isError(),
        "fixture template YAML must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private void assertValidatesAgainst(JsonNode instanceJson, String templateJson) throws Exception
  {
    McpSchema.CallToolResult result = ValidateInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance_artifact", Map.of(
            "schema_artifact", templateJson,
            "instance_artifact", jackson.writeValueAsString(instanceJson))));
    assertFalse(result.isError(), errorText(result));
    JsonNode report = jackson.readTree(textOf(result));
    assertTrue(report.path("valid").asBoolean(),
        "skeleton instance must validate against its template; got report:\n"
            + report.toPrettyString());
  }

  /**
   * The tool now returns the instance as expanded YAML. Read it back to the model and render
   * its JSON so the existing JSON-key assertions (schema:name, child objects, @type, arrays)
   * still apply — and so this exercises the instance YAML round trip end to end.
   */
  /**
   * Inflate the created (sparse) instance against its template and render the complete CEDAR
   * JSON, so structural assertions (child objects, @type seeds, nested elements) apply to the
   * full instance — which is exactly what validation and export operate on.
   */
  private ObjectNode inflatedJson(McpSchema.CallToolResult result, String templateJson) throws Exception
  {
    McpSchema.CallToolResult json = RenderInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_instance_artifact", Map.of(
            "instance_artifact", textOf(result), "template_artifact", templateJson, "format", "json")));
    assertFalse(json.isError(), errorText(json));
    return (ObjectNode) jackson.readTree(textOf(json));
  }

  @SuppressWarnings("unchecked")
  private ObjectNode parseJson(McpSchema.CallToolResult result)
  {
    String text = textOf(result);
    Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
    assertTrue(parsed instanceof java.util.Map, "result must be a YAML mapping; got: " + text);
    java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
    ((java.util.Map<Object, Object>) parsed).forEach((k, v) -> map.put(String.valueOf(k), v));
    var instance = ArtifactExchange.readTemplateInstanceYaml(map);
    return new org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer()
        .renderTemplateInstanceArtifact(instance);
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
  /** The minted @id the fixture template carries; isBasedOn must always equal it. */
  private String templateId(String templateYaml)
  {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?m)^id: (\\S+)$").matcher(templateYaml);
    assertTrue(m.find(), "fixture template must carry a minted id:; got:\n" + templateYaml);
    return m.group(1);
  }

  @Test void template_without_id_is_rejected_with_guidance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", "type: template\nname: No Identity\n"));
    assertTrue(result.isError(), "an id-less template cannot yield an isBasedOn");
    assertTrue(errorText(result).contains("@id"), errorText(result));
    assertTrue(errorText(result).contains("isBasedOn"), errorText(result));
  }

  @Test void a_compact_template_is_filled_when_the_stored_template_is_named()
  {
    // A template in the compact form names no artifact, so the caller says which stored template the
    // instance belongs to. This is the flow after authoring: save the template, then fill it.
    String storedTemplate = "https://repo.metadatacenter.org/templates/5c48700a-4163-436d-8daa-95af7311cded";
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", "type: template\nname: No Identity\n",
        "isBasedOn", storedTemplate));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains(storedTemplate), textOf(result));
  }

}

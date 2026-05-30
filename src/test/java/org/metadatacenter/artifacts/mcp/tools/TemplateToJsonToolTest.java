package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the {@code template_to_json} tool.
 *
 * <p>The headline case mirrors {@code JsonArtifactRendererTest}'s validation invariant:
 * a YAML input compiles to a CEDAR JSON Schema that {@link CedarValidator#validateTemplate}
 * accepts.
 */
final class TemplateToJsonToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void compiles_minimal_template_yaml_to_validated_json_schema() throws Exception
  {
    String yaml =
        "type: template\n"
            + "name: Patient demographics\n"
            + "description: Minimal demographics template\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the compiled template:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }

    assertEquals("Patient demographics", rendered.get("schema:name").asText());
    assertEquals("Minimal demographics template", rendered.get("schema:description").asText());
    assertEquals("0.1.0", rendered.get("pav:version").asText());
  }

  @Test void compiles_template_with_a_text_field_child() throws Exception
  {
    // Each child needs a `key` (its property identifier in the parent) and a `type`;
    // `name` and `description` are the human-readable labels carried into the rendered
    // template's _ui block.
    String yaml =
        "type: template\n"
            + "name: One-field template\n"
            + "description: A template with one text field\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n"
            + "    description: Free-text patient name\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    // CEDAR templates expose child fields as properties.<key>.
    JsonNode patientName = rendered.path("properties").path("patient_name");
    assertTrue(patientName.isObject(),
        "child field 'patient_name' should appear under properties; got: " + rendered.path("properties"));

    // Defense in depth: validate again externally.
    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "compiled template must pass CedarValidator");
  }

  @Test void compiles_template_with_a_controlled_term_field_class_constraint() throws Exception
  {
    // The central CEDAR authoring use case: a controlled-term field bound to a class
    // in an ontology. The tuple (iri, acronym, label, termLabel) is exactly what
    // bioportal-term-mcp's get_class returns, which is the integration story we're
    // building towards.
    String yaml =
        "type: template\n"
            + "name: Diagnosis template\n"
            + "description: Template with a controlled-term diagnosis field\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: diagnosis\n"
            + "    type: controlled-term-field\n"
            + "    name: Primary diagnosis\n"
            + "    description: Diagnosis from the Human Disease Ontology\n"
            + "    datatype: iri\n"
            + "    values:\n"
            + "      - type: class\n"
            + "        label: disease\n"
            + "        acronym: DOID\n"
            + "        termType: class\n"
            + "        termLabel: disease\n"
            + "        iri: http://purl.obolibrary.org/obo/DOID_4\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "controlled-term template must pass CedarValidator");

    JsonNode field = rendered.path("properties").path("diagnosis");
    assertTrue(field.isObject(),
        "diagnosis field must appear under properties; got: " + rendered.path("properties"));
  }

  @Test void compiles_template_with_multiple_field_types() throws Exception
  {
    // Realistic small template: name, age, DOB, email. Exercises four different field
    // types in one go — catches the kind of bug where one field-type path works in
    // isolation but breaks when composed with another.
    String yaml =
        "type: template\n"
            + "name: Patient intake\n"
            + "description: Mixed-field-type template\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n"
            + "  - key: age\n"
            + "    type: numeric-field\n"
            + "    name: Age\n"
            + "  - key: dob\n"
            + "    type: temporal-field\n"
            + "    name: Date of birth\n"
            + "    granularity: day\n"
            + "  - key: contact_email\n"
            + "    type: email-field\n"
            + "    name: Contact email\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "mixed-field-type template must pass CedarValidator; errors: "
            + (report.getErrors() == null ? "(none)" : report.getErrors()));

    for (String key : new String[]{"patient_name", "age", "dob", "contact_email"}) {
      assertTrue(rendered.path("properties").path(key).isObject(),
          "field '" + key + "' must appear under properties; got keys: "
              + rendered.path("properties"));
    }
  }

  @Test void compiles_template_with_nested_element() throws Exception
  {
    // Template -> element -> field. Nested structure is the second-most-important
    // composition pattern after controlled terms (an "Address" element with street/city
    // child fields is the canonical example).
    String yaml =
        "type: template\n"
            + "name: With nested element\n"
            + "description: Template with one element that has one field\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: address\n"
            + "    type: element\n"
            + "    name: Address\n"
            + "    description: Postal address element\n"
            + "    modelVersion: 1.6.0\n"
            + "    children:\n"
            + "      - key: street\n"
            + "        type: text-field\n"
            + "        name: Street\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "nested-element template must pass CedarValidator");

    JsonNode address = rendered.path("properties").path("address");
    assertTrue(address.isObject(), "address element should appear under properties");
    JsonNode street = address.path("properties").path("street");
    assertTrue(street.isObject(),
        "street field should appear under address.properties; got: " + address.path("properties"));
  }

  @Test void produces_deterministic_output_for_same_input() throws Exception
  {
    // If two invocations with the same input produce different output, something
    // is reading wall-clock time, a random UUID, or a system property into the
    // rendered JSON. That would break caching, diffing, and idempotent workflows.
    // The @id is pinned explicitly here: an omitted @id is auto-minted with a fresh
    // UUID per call (DESIGN.md Principle 10), which is the one deliberate source of
    // nondeterminism — see mints_a_fresh_id_per_call_when_omitted. Pinning it isolates
    // the rest of the render, which must stay byte-identical.
    String yaml =
        "type: template\n"
            + "name: Deterministic\n"
            + "description: A deterministic template\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "id: https://repo.metadatacenter.org/templates/deterministic-fixed-id\n";

    String first = textOf(invoke(Map.of("yaml", yaml)));
    String second = textOf(invoke(Map.of("yaml", yaml)));

    assertEquals(first, second,
        "two invocations on identical fully-specified input must produce byte-identical output");
  }

  @Test void mints_top_level_id_when_omitted() throws Exception
  {
    // Auto-mint convenience (DESIGN.md Principle 10): a top-level artifact with no id
    // gets a fresh CEDAR template IRI of the correct form.
    String yaml =
        "type: template\n"
            + "name: No id supplied\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    MintedIds.assertMintedId(rendered.get("@id"), "templates");
  }

  @Test void mints_a_fresh_id_per_call_when_omitted() throws Exception
  {
    String yaml =
        "type: template\n"
            + "name: No id supplied\n";

    String firstId = parseJson(invoke(Map.of("yaml", yaml))).get("@id").asText();
    String secondId = parseJson(invoke(Map.of("yaml", yaml))).get("@id").asText();

    assertFalse(firstId.equals(secondId),
        "each id-less invocation must mint a distinct @id; got " + firstId + " twice");
  }

  @Test void preserves_supplied_top_level_id() throws Exception
  {
    String id = "https://repo.metadatacenter.org/templates/abc-123";
    String yaml =
        "type: template\n"
            + "name: Has an id\n"
            + "id: " + id + "\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    assertEquals(id, parseJson(result).get("@id").asText(),
        "a supplied top-level id must be preserved, not overwritten by minting");
  }

  @Test void does_not_mint_ids_for_nested_children() throws Exception
  {
    // The minting seam is top-level only: nested elements and fields stay id-less unless
    // the author set one explicitly (DESIGN.md Principle 10).
    String yaml =
        "type: template\n"
            + "name: Nested, no child ids\n"
            + "children:\n"
            + "  - key: address\n"
            + "    type: element\n"
            + "    name: Address\n"
            + "    children:\n"
            + "      - key: street\n"
            + "        type: text-field\n"
            + "        name: Street\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    MintedIds.assertMintedId(rendered.get("@id"), "templates");

    JsonNode element = rendered.path("properties").path("address");
    MintedIds.assertNoId(element.path("@id"), "nested element 'address'");
    JsonNode field = element.path("properties").path("street");
    MintedIds.assertNoId(field.path("@id"), "nested field 'street'");
  }

  @Test void propagates_field_name_to_ui_property_labels() throws Exception
  {
    // The _ui block carries the human-readable label and description for each child
    // — this is what CEDAR's UI surfaces in form-rendering. If a transcode silently
    // drops the label, the rendered form is unusable even though the underlying
    // JSON Schema validates.
    String yaml =
        "type: template\n"
            + "name: UI test\n"
            + "description: Verifies _ui propagation\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n"
            + "    description: Free-text patient name\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));
    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode order = rendered.path("_ui").path("order");
    assertTrue(order.isArray(), "_ui.order must be an array; got: " + rendered.path("_ui"));
    assertEquals(1, order.size(), "expected one child in _ui.order");
    assertEquals("patient_name", order.get(0).asText());

    assertEquals("Patient name",
        rendered.path("_ui").path("propertyLabels").path("patient_name").asText(),
        "_ui.propertyLabels.patient_name should carry the child's name");
    assertEquals("Free-text patient name",
        rendered.path("_ui").path("propertyDescriptions").path("patient_name").asText(),
        "_ui.propertyDescriptions.patient_name should carry the child's description");
  }

  @Test void carries_unicode_through_template_name_and_description() throws Exception
  {
    // Charset safety: CEDAR templates routinely carry non-ASCII labels (clinical
    // research is multilingual). If we drop UTF-8 anywhere in the stdio /
    // Jackson / SnakeYAML chain, the bug is silent until a real user notices.
    String yaml =
        "type: template\n"
            + "name: \"Pétient démographics — 患者 — 🩺\"\n"
            + "description: \"Description with em-dash — and arrow → and CJK 患者\"\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));
    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Pétient démographics — 患者 — 🩺", rendered.path("schema:name").asText());
    assertEquals("Description with em-dash — and arrow → and CJK 患者",
        rendered.path("schema:description").asText());
  }

  @Test void accepts_yaml_missing_modelVersion_and_defaults_it() throws Exception
  {
    // The MCP uses the library reader's compact mode, which defaults missing
    // modelVersion to the library-canonical value. That's the round-trip story for
    // template_to_yaml's compact output, which omits modelVersion by design.
    String yaml =
        "type: template\n"
            + "name: Missing-model-version\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("Missing-model-version", rendered.get("schema:name").asText());
  }

  @Test void rejects_yaml_with_wrong_modelVersion()
  {
    // Defaulting in compact mode covers absence only. A present-but-wrong modelVersion
    // is still rejected, otherwise stale-version YAML would silently bind to the
    // current schema.
    String yaml =
        "type: template\n"
            + "name: Wrong-model-version\n"
            + "modelVersion: 0.0.1\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "wrong modelVersion must surface as isError=true; got: " + result);
    assertTrue(errorText(result).toLowerCase().contains("model version"),
        "error should mention model version; got: " + errorText(result));
  }

  @Test void rejects_blank_yaml()
  {
    McpSchema.CallToolResult result = invoke(Map.of("yaml", "   \n  \n"));
    assertTrue(result.isError(), "blank yaml input must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("yaml"),
        "error should mention the offending argument; got: " + errorText(result));
  }

  @Test void rejects_yaml_with_wrong_top_level_type()
  {
    // type: element passed to a template-compiling tool — should fail at the reader,
    // not silently produce an element JSON.
    String yaml =
        "type: element\n"
            + "name: NotATemplate\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "type: element must not compile via template_to_json; got: " + result);
    assertTrue(errorText(result).toLowerCase().contains("template"),
        "error should explain that template was expected; got: " + errorText(result));
  }

  @Test void rejects_malformed_yaml_with_clean_error()
  {
    // Tab indentation inside a mapping triggers SnakeYAML's scanner error — the kind of
    // mistake an LLM will reliably make.
    String yaml = "type: template\n\tname: malformed\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(), "malformed yaml must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("yaml"),
        "error should identify yaml as the problem area; got: " + errorText(result));
  }

  @Test void rejects_yaml_that_is_a_bare_string()
  {
    McpSchema.CallToolResult result = invoke(Map.of("yaml", "just a string\n"));
    assertTrue(result.isError(), "non-mapping yaml must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("mapping"),
        "error should mention the missing top-level mapping; got: " + errorText(result));
  }

  @Test void rejects_missing_yaml_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("yaml"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("template_to_json", arguments);
    return TemplateToJsonTool.handler(null, request);
  }

  private ObjectNode parseJson(McpSchema.CallToolResult result) throws Exception
  {
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    JsonNode node = jackson.readTree(text);
    assertTrue(node.isObject(), "result must be a JSON object; got: " + text);
    return (ObjectNode) node;
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    assertFalse(result.isError(), "expected non-error result, got: " + errorText(result));
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

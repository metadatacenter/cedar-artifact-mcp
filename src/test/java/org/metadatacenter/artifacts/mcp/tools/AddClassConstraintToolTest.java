package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AddClassConstraintToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void adds_class_constraint_to_controlled_term_field() throws Exception
  {
    String fieldJson = createControlledTermField("Disease");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "class_iri", "http://purl.obolibrary.org/obo/DOID_4",
        "ontology_acronym", "DOID",
        "label", "disease",
        "pref_label", "disease"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode values = rendered.path("_valueConstraints").path("classes");
    assertTrue(values.isArray() && values.size() == 1,
        "_valueConstraints.classes must carry one entry; got: "
            + rendered.path("_valueConstraints"));
    assertEquals("http://purl.obolibrary.org/obo/DOID_4", values.get(0).path("uri").asText());

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus(),
        "constrained field must pass validateTemplateField");
  }

  @Test void promotes_text_shaped_field_when_constraint_added() throws Exception
  {
    // An empty controlled-term-field is JSON-indistinguishable from a text-field; the
    // library only classifies a TEXTFIELD as controlled-term once it carries a constraint.
    // So feeding a text-field here is the same call path as feeding a freshly-created
    // controlled-term-field, and both must produce a valid constrained field.
    String textFieldJson = createField("Some IRI", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", textFieldJson,
        "class_iri", "http://purl.obolibrary.org/obo/DOID_4",
        "ontology_acronym", "DOID",
        "label", "disease",
        "pref_label", "disease"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertTrue(rendered.path("_valueConstraints").path("classes").isArray()
            && rendered.path("_valueConstraints").path("classes").size() == 1,
        "rendered field must carry the class constraint; got: " + rendered.path("_valueConstraints"));
  }

  @Test void rejects_non_textfield_shape()
  {
    // Numeric, temporal, list, etc. fields have non-TEXTFIELD input types and can't carry
    // a controlled-term constraint. Reject them with a clean message.
    String numericFieldJson = createField("Count", "numeric-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", numericFieldJson,
        "class_iri", "http://example.com/x",
        "ontology_acronym", "X",
        "label", "x",
        "pref_label", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("text-field")
            || errorText(result).toLowerCase().contains("controlled-term"),
        "error should mention text-field/controlled-term requirement; got: " + errorText(result));
  }

  @Test void rejects_invalid_class_iri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", createControlledTermField("X"),
        "class_iri", "not a uri with spaces",
        "ontology_acronym", "X",
        "label", "x",
        "pref_label", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("class_iri"));
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", createControlledTermField("X")));
    assertTrue(result.isError());
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return AddClassConstraintTool.handler(null,
        new McpSchema.CallToolRequest("add_class_constraint", arguments));
  }

  private String createControlledTermField(String name)
  {
    return createField(name, "controlled-term-field");
  }

  private String createField(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "fixture field must build cleanly; got: " + errorText(result));
    return textOf(result);
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

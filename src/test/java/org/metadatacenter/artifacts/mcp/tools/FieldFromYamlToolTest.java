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
 * Tests for the {@code field_from_yaml} tool. Mirrors {@link TemplateFromYamlToolTest}'s
 * shape: a YAML input compiles to a CEDAR JSON Schema that
 * {@link CedarValidator#validateTemplateField} accepts.
 */
final class FieldFromYamlToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void compiles_minimal_text_field_yaml_to_validated_json_schema() throws Exception
  {
    String yaml =
        "type: text-field\n"
            + "name: Patient name\n"
            + "description: Free-text patient name\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the compiled field:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }

    assertEquals("Patient name", rendered.get("schema:name").asText());
  }

  @Test void compiles_controlled_term_field_with_class_constraint() throws Exception
  {
    // The central CEDAR authoring use case at field granularity: a controlled-term
    // field bound to a class in an ontology. Same (iri, acronym, label) tuple shape
    // as bioportal-term-mcp's get_class output.
    String yaml =
        "type: controlled-term-field\n"
            + "name: Primary diagnosis\n"
            + "description: Diagnosis from the Human Disease Ontology\n"
            + "modelVersion: 1.6.0\n"
            + "datatype: iri\n"
            + "values:\n"
            + "  - type: class\n"
            + "    label: disease\n"
            + "    acronym: DOID\n"
            + "    termType: class\n"
            + "    termLabel: disease\n"
            + "    iri: http://purl.obolibrary.org/obo/DOID_4\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus(),
        "controlled-term field must pass CedarValidator");
  }

  @Test void mints_top_level_id_when_omitted() throws Exception
  {
    // Fields are first-class, reusable CEDAR artifacts: a standalone field with no id
    // gets a fresh template-fields IRI (DESIGN.md Principle 10).
    String yaml =
        "type: text-field\n"
            + "name: No id supplied\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    MintedIds.assertMintedId(parseJson(result).get("@id"), "template-fields");
  }

  @Test void preserves_supplied_id() throws Exception
  {
    String id = "https://repo.metadatacenter.org/template-fields/abc-123";
    String yaml =
        "type: text-field\n"
            + "name: Has an id\n"
            + "id: " + id + "\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    assertEquals(id, parseJson(result).get("@id").asText(),
        "a supplied id must be preserved, not overwritten by minting");
  }

  @Test void rejects_yaml_whose_top_level_type_is_template()
  {
    String yaml =
        "type: template\n"
            + "name: NotAField\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "type: template must not compile via field_from_yaml; got: " + result);
  }

  @Test void rejects_missing_yaml_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("yaml"));
  }

  @Test void rejects_blank_yaml()
  {
    McpSchema.CallToolResult result = invoke(Map.of("yaml", "   \n  \n"));
    assertTrue(result.isError(), "blank yaml input must produce isError=true");
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return FieldFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("field_from_yaml", arguments));
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
}

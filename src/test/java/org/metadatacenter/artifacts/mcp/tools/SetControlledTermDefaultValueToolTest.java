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

final class SetControlledTermDefaultValueToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void sets_controlled_term_default() throws Exception
  {
    String fieldJson = compileField(
        "type: controlled-term-field\n"
            + "name: Diagnosis\n"
            + "description: ICD diagnosis\n"
            + "modelVersion: 1.6.0\n"
            + "datatype: iri\n"
            + "values:\n"
            + "  - type: class\n"
            + "    label: disease\n"
            + "    acronym: DOID\n"
            + "    termType: class\n"
            + "    termLabel: disease\n"
            + "    iri: http://purl.obolibrary.org/obo/DOID_4\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    JsonNode def = rendered.path("_valueConstraints").path("defaultValue");
    assertEquals("http://purl.obolibrary.org/obo/DOID_1612",
        def.path("termUri").asText(),
        "default IRI must appear; got: " + def);
    assertEquals("breast cancer", def.path("rdfs:label").asText(),
        "default label must appear; got: " + def);

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void rejects_text_field_without_constraint()
  {
    // A plain text-field reads back as TextField (the wire collision); the tool must
    // refuse and direct the user to add_*_constraint first.
    String fieldJson = createField("Note", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "iri", "https://example.org/x",
        "label", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("text-field")
            || errorText(result).toLowerCase().contains("controlled-term"),
        "error should redirect to constraint tools; got: " + errorText(result));
  }

  @Test void rejects_missing_label()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", "{}",
        "iri", "https://x.example"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("label"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetControlledTermDefaultValueTool.handler(null,
        new McpSchema.CallToolRequest("set_controlled_term_default_value", args));
  }

  private static String createField(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "fixture field must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String compileField(String yaml)
  {
    McpSchema.CallToolResult result = FieldFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("field_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture field YAML must compile cleanly; got: " + errorText(result));
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

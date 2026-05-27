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

final class AddDefaultValueToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void sets_text_field_default() throws Exception
  {
    String fieldJson = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "value", "Alice"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("Alice",
        rendered.path("_valueConstraints").path("defaultValue").asText(),
        "default value must appear under _valueConstraints; got: "
            + rendered.path("_valueConstraints"));

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void sets_text_area_field_default() throws Exception
  {
    // Text-area used to be unsupported because TextAreaField.Builder lacked
    // withDefaultValue; the library now exposes it, so this tool covers it too.
    String fieldJson = createField("Notes", "text-area-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "value", "Initial notes here"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("Initial notes here",
        rendered.path("_valueConstraints").path("defaultValue").asText());
  }

  @Test void sets_temporal_field_default() throws Exception
  {
    String fieldJson = createField("DOB", "temporal-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "value", "2026-01-01"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("2026-01-01",
        rendered.path("_valueConstraints").path("defaultValue").asText(),
        "temporal default value must appear under _valueConstraints");
  }

  @Test void sets_numeric_field_default() throws Exception
  {
    // Numeric defaults must serialize as JSON strings (not bare numbers). The CEDAR
    // validator rejects bare numbers at _valueConstraints.defaultValue, so this test
    // both pins the wire shape and exercises the full validate path.
    String fieldJson = createField("Age", "numeric-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "value", "42"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    JsonNode defaultNode = rendered.path("_valueConstraints").path("defaultValue");
    assertTrue(defaultNode.isTextual(),
        "numeric defaultValue must be a JSON string, not a number; got: " + defaultNode);
    assertEquals("42", defaultNode.asText());

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus(),
        "numeric field with default must pass CEDAR validation; report: " + report);
  }

  @Test void rejects_controlled_term_field()
  {
    // Direct add_default_value on a controlled-term field must redirect to the
    // controlled-term default tool.
    String fieldJson = compileField(
        "type: controlled-term-field\n"
            + "name: Diagnosis\n"
            + "description: D\n"
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
        "value", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("add_controlled_term_default_value"),
        "error should redirect; got: " + errorText(result));
  }

  @Test void rejects_iri_field()
  {
    String fieldJson = createField("ROR", "ext-ror-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "value", "https://ror.org/x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("add_iri_default_value")
            || errorText(result).contains("IRI"),
        "error should redirect to IRI variant; got: " + errorText(result));
  }

  @Test void rejects_missing_value()
  {
    String fieldJson = createField("X", "text-field");
    McpSchema.CallToolResult result = invoke(Map.of("field_json", fieldJson));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("value"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return AddDefaultValueTool.handler(null,
        new McpSchema.CallToolRequest("add_default_value", args));
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

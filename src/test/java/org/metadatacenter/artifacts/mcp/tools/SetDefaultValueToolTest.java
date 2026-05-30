package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SetDefaultValueToolTest
{
  private ModelValidator cedarValidator;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
  }

  @Test void sets_text_field_default() throws Exception
  {
    String fieldYaml = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldYaml,
        "value", "Alice"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(result);
    assertEquals("Alice",
        rendered.path("_valueConstraints").path("defaultValue").asText(),
        "default value must appear under _valueConstraints; got: "
            + rendered.path("_valueConstraints"));

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void sets_text_area_field_default()
  {
    // Text-area used to be unsupported because TextAreaField.Builder lacked
    // withDefaultValue; the library now exposes it, so this tool covers it too.
    String fieldYaml = createField("Notes", "text-area-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldYaml,
        "value", "Initial notes here"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(result);
    assertEquals("Initial notes here",
        rendered.path("_valueConstraints").path("defaultValue").asText());
  }

  @Test void sets_temporal_field_default()
  {
    String fieldYaml = createField("DOB", "temporal-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldYaml,
        "value", "2026-01-01"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(result);
    assertEquals("2026-01-01",
        rendered.path("_valueConstraints").path("defaultValue").asText(),
        "temporal default value must appear under _valueConstraints");
  }

  @Test void sets_numeric_field_default() throws Exception
  {
    // Numeric defaults must serialize as JSON strings (not bare numbers). The CEDAR
    // validator rejects bare numbers at _valueConstraints.defaultValue, so this test
    // both pins the wire shape and exercises the full validate path.
    String fieldYaml = createField("Age", "numeric-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldYaml,
        "value", "42"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(result);
    var defaultNode = rendered.path("_valueConstraints").path("defaultValue");
    assertTrue(defaultNode.isTextual(),
        "numeric defaultValue must be a JSON string, not a number; got: " + defaultNode);
    assertEquals("42", defaultNode.asText());

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus(),
        "numeric field with default must pass CEDAR validation; report: " + report);
  }

  @Test void rejects_controlled_term_field()
  {
    // Direct set_default_value on a controlled-term field must redirect to the
    // controlled-term default tool. Build the controlled-term field by creating an
    // empty one and attaching a class constraint (both return YAML).
    String fieldYaml = constrainedControlledTermField();

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldYaml,
        "value", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_controlled_term_default_value"),
        "error should redirect; got: " + errorText(result));
  }

  @Test void rejects_iri_field()
  {
    String fieldYaml = createField("ROR", "ext-ror-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldYaml,
        "value", "https://ror.org/x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_iri_default_value")
            || errorText(result).contains("IRI"),
        "error should redirect to IRI variant; got: " + errorText(result));
  }

  @Test void rejects_missing_value()
  {
    String fieldYaml = createField("X", "text-field");
    McpSchema.CallToolResult result = invoke(Map.of("field_json", fieldYaml));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("value"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetDefaultValueTool.handler(null,
        new McpSchema.CallToolRequest("set_default_value", args));
  }

  private static String createField(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "fixture field must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  /** A controlled-term field with one class constraint attached — built entirely from YAML-returning tools. */
  private static String constrainedControlledTermField()
  {
    String empty = createField("Diagnosis", "controlled-term-field");
    McpSchema.CallToolResult result = SetClassConstraintTool.handler(null,
        new McpSchema.CallToolRequest("set_class_constraint", Map.of(
            "field_json", empty,
            "class_iri", "http://purl.obolibrary.org/obo/DOID_4",
            "ontology_acronym", "DOID",
            "label", "disease",
            "pref_label", "disease")));
    assertFalse(result.isError(),
        "fixture constraint must apply cleanly; got: " + errorText(result));
    return textOf(result);
  }

  /** Read the tool's YAML output back to the model, then render JSON for assertions. */
  private static ObjectNode renderJson(McpSchema.CallToolResult result)
  {
    // Parse via ArtifactExchange so date-like temporal values stay strings (no SnakeYAML
    // auto-typing to java.util.Date), matching how the threading tools read YAML.
    LinkedHashMap<String, Object> map = ArtifactExchange.parseYamlMap(textOf(result));
    FieldSchemaArtifact field = new YamlArtifactReader(true).readFieldSchemaArtifact(map);
    return new JsonArtifactRenderer().renderFieldSchemaArtifact(field);
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

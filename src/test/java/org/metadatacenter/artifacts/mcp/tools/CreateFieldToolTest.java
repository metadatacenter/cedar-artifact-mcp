package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the {@code create_field} tool. The headline test is the parameterized
 * type-coverage one: every kebab-case wire type in
 * {@link org.metadatacenter.artifacts.model.yaml.YamlConstants#FIELD_TYPES} must build
 * an empty field that passes {@link CedarValidator#validateTemplateField}.
 *
 * <p>The tool now returns the field as expanded YAML (the exchange form). The validity
 * tests parse that YAML, read it back to the model, and validate its JSON rendering.
 */
final class CreateFieldToolTest
{
  private ModelValidator cedarValidator;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "text-field", "controlled-term-field", "text-area-field", "numeric-field",
      "temporal-field", "radio-field", "checkbox-field",
      "single-select-list-field", "multi-select-list-field",
      "phone-number-field", "email-field", "link-field",
      "ext-ror-field", "ext-orcid-field", "ext-pfas-field", "ext-rrid-field",
      "ext-pubmed-field", "ext-nih-grant-id-field", "ext-doi-field",
      "attribute-value-field",
      "static-page-break", "static-section-break", "static-image",
      "static-rich-text", "static-youtube-video"})
  void everyKnownFieldType_buildsAValidEmptyShell(String type) throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Test " + type,
        "type", type));

    assertFalse(result.isError(),
        "type " + type + " should build cleanly; got: " + errorText(result));
    ObjectNode rendered = renderJson(parseYaml(result));

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder(
          "CedarValidator rejected empty " + type + " field:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }
  }

  @Test void multi_select_list_field_builds_a_valid_field() throws Exception
  {
    // The single-select / multi-select distinction lives in valueConstraints.multipleChoice,
    // which expanded YAML does not surface as a standalone key. Assert the wire type produces
    // a field the canonical validator accepts after a YAML -> model -> JSON round trip.
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Favorites",
        "type", "multi-select-list-field"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(parseYaml(result));
    assertEquals("true", cedarValidator.validateTemplateField(rendered).getValidationStatus(),
        "multi-select-list-field must build a valid field; rendered:\n" + rendered);
  }

  @Test void single_select_list_field_builds_a_valid_field() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Pick one",
        "type", "single-select-list-field"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(parseYaml(result));
    assertEquals("true", cedarValidator.validateTemplateField(rendered).getValidationStatus(),
        "single-select-list-field must build a valid field; rendered:\n" + rendered);
  }

  @Test void rejects_unknown_field_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "X",
        "type", "not-a-real-field-type"));
    assertTrue(result.isError(), "unknown type must produce isError=true");
    assertTrue(errorText(result).contains("not-a-real-field-type"));
  }

  @Test void rejects_missing_name()
  {
    McpSchema.CallToolResult result = invoke(Map.of("type", "text-field"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("name"));
  }

  @Test void rejects_missing_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "X"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("type"));
  }

  @Test void rejects_invalid_version()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "X",
        "type", "text-field",
        "version", "garbage"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("version"));
  }

  @Test void createField_setsJsonLdIdWhenAbsoluteIriSupplied() throws Exception
  {
    String id = "https://repo.metadatacenter.org/template-fields/abc-123";
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient name",
        "type", "text-field",
        "id", id));

    assertFalse(result.isError(), "a valid absolute IRI id should succeed");
    Map<String, Object> yaml = parseYaml(result);
    assertEquals(id, yaml.get("id"));
  }

  @Test void createField_mintsIdWhenOmitted() throws Exception
  {
    // Fields are first-class, reusable CEDAR artifacts, so a standalone field minted with
    // no id gets a fresh template-fields IRI like any other root (DESIGN.md Principle 10).
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient name",
        "type", "text-field"));

    assertFalse(result.isError(), "omitting id should still succeed");
    Map<String, Object> yaml = parseYaml(result);
    MintedIds.assertMintedId((String) yaml.get("id"), "template-fields");
  }

  @Test void createField_rejectsRelativeIri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient name",
        "type", "text-field",
        "id", "template-fields/abc-123"));

    assertTrue(result.isError(), "a non-absolute IRI id should produce an error result");
    assertTrue(errorText(result).toLowerCase().contains("absolute"),
        "error message should explain the id must be absolute, got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // Per-type configuration: numeric
  // -----------------------------------------------------------------

  @Test void numeric_field_with_datatype_xsd_int_carries_through() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Age",
        "type", "numeric-field",
        "datatype", "xsd:int"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);
    assertEquals("xsd:int", yaml.get("datatype"));
  }

  @Test void numeric_field_with_min_max_unit_decimal_places() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "pH",
        "type", "numeric-field",
        "datatype", "xsd:decimal",
        "min_value", 0,
        "max_value", 14,
        "decimal_places", 2,
        "unit", "pH"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);
    assertEquals("xsd:decimal", yaml.get("datatype"));
    assertEquals(0, asInt(yaml.get("minValue")));
    assertEquals(14, asInt(yaml.get("maxValue")));
    assertEquals(2, asInt(yaml.get("decimalPlaces")));
    assertEquals("pH", yaml.get("unit"));
  }

  @Test void numeric_field_rejects_invalid_datatype()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "X",
        "type", "numeric-field",
        "datatype", "xsd:garbage"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("datatype"),
        "error must mention datatype; got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // Per-type configuration: temporal
  // -----------------------------------------------------------------

  @Test void temporal_field_with_datatype_and_granularity() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Birthdate",
        "type", "temporal-field",
        "datatype", "xsd:date",
        "granularity", "day"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);
    assertEquals("xsd:date", yaml.get("datatype"));
    assertEquals("day", yaml.get("granularity"));
  }

  @Test void temporal_field_with_input_time_format_and_zone() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Appointment",
        "type", "temporal-field",
        "datatype", "xsd:dateTime",
        "granularity", "minute",
        "input_time_format", "24h",
        "input_time_zone", true));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);
    assertEquals("minute", yaml.get("granularity"));
    assertEquals("24h", yaml.get("inputTimeFormat"));
    assertEquals(Boolean.TRUE, yaml.get("inputTimeZone"));
  }

  // -----------------------------------------------------------------
  // Per-type configuration: text / text-area
  // -----------------------------------------------------------------

  @Test void text_field_with_min_max_length_and_regex() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Postcode",
        "type", "text-field",
        "min_length", 5,
        "max_length", 5,
        "regex", "^[0-9]{5}$"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);
    assertEquals(5, asInt(yaml.get("minLength")));
    assertEquals(5, asInt(yaml.get("maxLength")));
    assertEquals("^[0-9]{5}$", yaml.get("regex"));
  }

  // -----------------------------------------------------------------
  // Cross-type misapplication: param doesn't fit chosen type
  // -----------------------------------------------------------------

  @Test void rejects_min_length_on_numeric_field()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "X",
        "type", "numeric-field",
        "min_length", 5));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("min_length"),
        "error must mention the misapplied key; got: " + errorText(result));
  }

  @Test void rejects_datatype_on_text_field()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "X",
        "type", "text-field",
        "datatype", "xsd:int"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("datatype"));
  }

  @Test void rejects_granularity_on_numeric_field()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "X",
        "type", "numeric-field",
        "granularity", "day"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("granularity"));
  }

  @Test void static_rich_text_accepts_content()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "static-rich-text", "name", "Intro",
            "content", "<p>Welcome to the study</p>")));

    assertFalse(result.isError(), errorText(result));
    assertTrue(errorText(result).contains("Welcome to the study"),
        "the rich-text body must reach the field; got: " + errorText(result));
  }

  @Test void static_image_accepts_content_and_dimensions()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "static-image", "name", "Logo",
            "content", "https://example.org/logo.png",
            "width", 640, "height", 480)));

    assertFalse(result.isError(), errorText(result));
    String yaml = errorText(result);
    assertTrue(yaml.contains("https://example.org/logo.png"), "image URL; got: " + yaml);
    assertTrue(yaml.contains("640") && yaml.contains("480"), "dimensions; got: " + yaml);
  }

  @Test void static_youtube_accepts_content_and_dimensions()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "static-youtube-video", "name", "Intro video",
            "content", "https://youtube.com/watch?v=xyz",
            "width", 1280, "height", 720)));

    assertFalse(result.isError(), errorText(result));
    String yaml = errorText(result);
    assertTrue(yaml.contains("https://youtube.com/watch?v=xyz"), "video URL; got: " + yaml);
    assertTrue(yaml.contains("1280") && yaml.contains("720"), "dimensions; got: " + yaml);
  }

  @Test void rejects_content_on_a_non_static_type()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "text-field", "name", "Name", "content", "nope")));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("static fields only"), errorText(result));
  }

  @Test void rejects_dimensions_on_rich_text()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "static-rich-text", "name", "Intro",
            "content", "<p>x</p>", "width", 640)));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("static-image and static-youtube-video only"), errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", arguments));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + text);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<Object, Object> e : ((Map<Object, Object>) parsed).entrySet())
      map.put(String.valueOf(e.getKey()), e.getValue());
    return map;
  }

  /** Read the YAML field map back to the model and render its JSON for validation. */
  private static ObjectNode renderJson(Map<String, Object> yaml)
  {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(yaml);
    FieldSchemaArtifact model = new YamlArtifactReader(true).readFieldSchemaArtifact(map);
    return new JsonArtifactRenderer().renderFieldSchemaArtifact(model);
  }

  private static int asInt(Object value)
  {
    assertNotNull(value, "expected a numeric value, got null");
    return ((Number) value).intValue();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

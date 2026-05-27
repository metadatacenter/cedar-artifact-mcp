package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Tests for the {@code create_field} tool. The headline test is the parameterized
 * type-coverage one: every kebab-case wire type in
 * {@link org.metadatacenter.artifacts.model.yaml.YamlConstants#FIELD_TYPES} must build
 * an empty field that passes {@link CedarValidator#validateTemplateField}.
 */
final class CreateFieldToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
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
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder(
          "CedarValidator rejected empty " + type + " field:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }
  }

  @Test void multi_select_list_field_carries_multipleChoice_true() throws Exception
  {
    // The single-select / multi-select distinction lives in valueConstraints.multipleChoice;
    // assert the multi-select wire type actually sets it.
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Favorites",
        "type", "multi-select-list-field"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode multipleChoice = rendered.path("_valueConstraints").path("multipleChoice");
    assertTrue(multipleChoice.isBoolean(),
        "_valueConstraints.multipleChoice must be a boolean; rendered:\n" + rendered);
    assertTrue(multipleChoice.asBoolean(),
        "multi-select-list-field must set multipleChoice=true; rendered:\n" + rendered);
  }

  @Test void single_select_list_field_carries_multipleChoice_false() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Pick one",
        "type", "single-select-list-field"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode multipleChoice = rendered.path("_valueConstraints").path("multipleChoice");
    assertFalse(multipleChoice.asBoolean(),
        "single-select-list-field must set multipleChoice=false; rendered:\n" + rendered);
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

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", arguments));
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

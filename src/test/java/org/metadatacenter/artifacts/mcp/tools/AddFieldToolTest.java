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
 * Tests for the {@code add_field} tool. Each test sources a parent JSON from the
 * {@code create_template} / {@code create_element} tools so the inputs are real
 * CEDAR JSON Schema, then asserts that the result still validates and that the
 * new child appears under {@code properties.<key>}.
 */
final class AddFieldToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void adds_text_field_to_template_parent() throws Exception
  {
    String templateJson = createTemplate("Demographics");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "field_type", "text-field",
        "key", "patient_name",
        "name", "Patient name",
        "description", "Free-text patient name"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode child = rendered.path("properties").path("patient_name");
    assertTrue(child.isObject(),
        "patient_name child must appear under properties; got: " + rendered.path("properties"));
    assertEquals("Patient name", child.path("schema:name").asText(),
        "child schema:name must match the supplied name");

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the updated template:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }
  }

  @Test void adds_required_text_field_and_template_marks_it_required() throws Exception
  {
    String templateJson = createTemplate("With required");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "field_type", "text-field",
        "key", "must_have",
        "name", "Must have",
        "required", true));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    // CEDAR templates push required fields into the top-level "required" array.
    JsonNode requiredArray = rendered.path("required");
    assertTrue(requiredArray.isArray(), "required must be an array; got: " + requiredArray);

    boolean found = false;
    for (JsonNode entry : requiredArray)
      if ("must_have".equals(entry.asText())) found = true;
    assertTrue(found, "required field key must appear in the template's 'required' array; got: " + requiredArray);
  }

  @Test void adds_controlled_term_field_to_element_parent() throws Exception
  {
    String elementJson = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", elementJson,
        "field_type", "controlled-term-field",
        "key", "country",
        "name", "Country"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode child = rendered.path("properties").path("country");
    assertTrue(child.isObject(),
        "country child must appear under the element's properties; got: " + rendered.path("properties"));

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated element must pass validateTemplateElement");
  }

  @Test void rejects_unknown_field_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", createTemplate("X"),
        "field_type", "not-a-real-field-type",
        "key", "x",
        "name", "X"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("not-a-real-field-type"));
  }

  @Test void rejects_parent_json_without_at_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", "{}",
        "field_type", "text-field",
        "key", "x",
        "name", "X"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"),
        "error should mention the missing @type; got: " + errorText(result));
  }

  @Test void rejects_parent_json_with_wrong_at_type()
  {
    // A bare field (not a template or element) is a valid CEDAR artifact but isn't a
    // parent — add_field must refuse it.
    String fieldJson = invokeCreateField("standalone", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", fieldJson,
        "field_type", "text-field",
        "key", "x",
        "name", "X"));
    assertTrue(result.isError(),
        "field artifact must not be accepted as a parent; got: " + result);
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    // First missing required arg surfaces — parent_json comes first in the validation.
    assertTrue(errorText(result).contains("parent_json"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", arguments));
  }

  private String createTemplate(String name)
  {
    McpSchema.CallToolResult result = CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name)));
    assertFalse(result.isError(),
        "test fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private String createElement(String name)
  {
    McpSchema.CallToolResult result = CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", Map.of("name", name)));
    assertFalse(result.isError(),
        "test fixture element must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private String invokeCreateField(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "test fixture field must build cleanly; got: " + errorText(result));
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

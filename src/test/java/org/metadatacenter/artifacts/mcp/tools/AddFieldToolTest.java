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

/**
 * Tests for the {@code add_field} tool. Parallels {@link AddElementToolTest}: both
 * tools take a pre-built child JSON, dispatch on parent {@code @type}, and return
 * the updated parent JSON revalidated by CedarValidator.
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

  @Test void adds_field_to_template_parent() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String fieldJson = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "patient_name"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode child = rendered.path("properties").path("patient_name");
    assertTrue(child.isObject(),
        "field must appear under properties.<key>; got: " + rendered.path("properties"));

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated template must pass validateTemplate");
  }

  @Test void adds_field_to_element_parent() throws Exception
  {
    String elementJson = createElement("Address");
    String fieldJson = createField("Country", "controlled-term-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", elementJson,
        "child_json", fieldJson,
        "key", "country"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("properties").path("country").isObject(),
        "country must appear under the element's properties");

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated element must pass validateTemplateElement");
  }

  @Test void name_override_appears_in_propertyLabels() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String fieldJson = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "patient_full_name",
        "name", "Patient full name"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Patient full name",
        rendered.path("_ui").path("propertyLabels").path("patient_full_name").asText(),
        "name override must surface in _ui.propertyLabels; got _ui: " + rendered.path("_ui"));
  }

  @Test void rejects_child_json_that_is_not_a_field()
  {
    // An element JSON must not be accepted as a field child — that's add_element's job.
    String templateJson = createTemplate("X");
    String elementJson = createElement("not-a-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", elementJson,
        "key", "x"));
    assertTrue(result.isError(),
        "an element JSON must not be accepted as a field child; got: " + result);
  }

  @Test void rejects_parent_without_at_type()
  {
    String fieldJson = createField("X", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", "{}",
        "child_json", fieldJson,
        "key", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"));
  }

  @Test void rejects_parent_with_field_at_type()
  {
    // A bare field is a valid CEDAR artifact but isn't a parent — add_field must refuse it.
    String fieldJson = createField("standalone", "text-field");
    String anotherFieldJson = createField("another", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", fieldJson,
        "child_json", anotherFieldJson,
        "key", "x"));
    assertTrue(result.isError(),
        "field artifact must not be accepted as a parent; got: " + result);
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
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
        "fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private String createElement(String name)
  {
    McpSchema.CallToolResult result = CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture element must build cleanly; got: " + errorText(result));
    return textOf(result);
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

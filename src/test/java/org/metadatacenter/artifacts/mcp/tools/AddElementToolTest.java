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
 * Tests for the {@code add_element} tool. Inputs are produced by the existing
 * {@code create_*} tools so the fixtures are real CEDAR JSON Schema.
 */
final class AddElementToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void adds_element_to_template_parent() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String elementJson = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", elementJson,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode child = rendered.path("properties").path("address");
    assertTrue(child.isObject(),
        "address element must appear under the template's properties; got: "
            + rendered.path("properties"));

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated template must pass validateTemplate");
  }

  @Test void adds_element_to_element_parent_nested() throws Exception
  {
    // Nested element-in-element is a valid composition shape (e.g. Person containing
    // Address). The tool must support it via the element parent branch.
    String outerJson = createElement("Person");
    String innerJson = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", outerJson,
        "child_json", innerJson,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("properties").path("address").isObject(),
        "address must appear under Person's properties");

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated element must pass validateTemplateElement");
  }

  @Test void name_override_appears_in_propertyLabels() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String elementJson = createElement("Address");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", elementJson,
        "key", "home_address",
        "name", "Home address"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode label = rendered.path("_ui").path("propertyLabels").path("home_address");
    assertEquals("Home address", label.asText(),
        "name override must surface in _ui.propertyLabels; got _ui: " + rendered.path("_ui"));
  }

  @Test void rejects_child_json_that_is_not_an_element() throws Exception
  {
    String templateJson = createTemplate("X");
    String fieldJson = createFieldJson("standalone", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "x"));
    assertTrue(result.isError(),
        "a field JSON must not be accepted as a child element; got: " + result);
  }

  @Test void rejects_parent_without_at_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", "{}",
        "child_json", createElement("X"),
        "key", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"));
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
    return AddElementTool.handler(null,
        new McpSchema.CallToolRequest("add_element", arguments));
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

  private String createFieldJson(String name, String type)
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

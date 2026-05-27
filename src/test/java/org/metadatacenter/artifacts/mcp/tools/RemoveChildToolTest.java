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

final class RemoveChildToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void removes_field_from_template() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Patient\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n"
            + "  - key: age\n"
            + "    type: numeric-field\n"
            + "    name: Age\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "key", "patient_name"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("properties").path("patient_name").isMissingNode(),
        "removed field must not appear under properties; got: " + rendered.path("properties"));
    assertTrue(rendered.path("properties").path("age").isObject(),
        "sibling field must still be present");

    // _ui.order should also have the removed key gone.
    JsonNode order = rendered.path("_ui").path("order");
    for (JsonNode entry : order)
      assertFalse("patient_name".equals(entry.asText()),
          "_ui.order must not still contain the removed key; got: " + order);

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated template must pass validateTemplate");
  }

  @Test void removes_element_from_template() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: With address\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: address\n"
            + "    type: element\n"
            + "    name: Address\n"
            + "    description: Postal\n"
            + "    modelVersion: 1.6.0\n"
            + "    children:\n"
            + "      - key: street\n"
            + "        type: text-field\n"
            + "        name: Street\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "key", "address"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("properties").path("address").isMissingNode(),
        "removed element must not appear under properties");

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void removes_field_from_element_parent() throws Exception
  {
    // Build an element with a field, then remove the field.
    String elementJson = compileElement(
        "type: element\n"
            + "name: Address\n"
            + "description: Postal\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: street\n"
            + "    type: text-field\n"
            + "    name: Street\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", elementJson,
        "key", "street"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertTrue(rendered.path("properties").path("street").isMissingNode(),
        "removed field must not appear under the element's properties");

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void rejects_unknown_key()
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: T\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "key", "nonexistent"));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nonexistent"));
  }

  @Test void rejects_parent_without_at_type()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", "{}",
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

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return RemoveChildTool.handler(null,
        new McpSchema.CallToolRequest("remove_child", args));
  }

  private static String compileTemplate(String yaml)
  {
    McpSchema.CallToolResult result = TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture template must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String compileElement(String yaml)
  {
    McpSchema.CallToolResult result = ElementFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("element_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture element must compile cleanly; got: " + errorText(result));
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

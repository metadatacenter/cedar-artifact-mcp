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
 * Tests for the {@code element_from_yaml} tool. Mirrors {@link TemplateFromYamlToolTest}'s
 * shape: a YAML input compiles to a CEDAR JSON Schema that
 * {@link CedarValidator#validateTemplateElement} accepts.
 */
final class ElementFromYamlToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void compiles_minimal_element_yaml_to_validated_json_schema() throws Exception
  {
    String yaml =
        "type: element\n"
            + "name: Address\n"
            + "description: Postal address element\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: street\n"
            + "    type: text-field\n"
            + "    name: Street\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the compiled element:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }

    assertEquals("Address", rendered.get("schema:name").asText());
    JsonNode street = rendered.path("properties").path("street");
    assertTrue(street.isObject(), "street child must appear under properties");
  }

  @Test void rejects_yaml_whose_top_level_type_is_template()
  {
    String yaml =
        "type: template\n"
            + "name: NotAnElement\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "type: template must not compile via element_from_yaml; got: " + result);
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

  @Test void rejects_malformed_yaml_with_clean_error()
  {
    String yaml = "type: element\n\tname: malformed\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(), "malformed yaml must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("yaml"),
        "error should identify yaml as the problem area; got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return ElementFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("element_from_yaml", arguments));
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

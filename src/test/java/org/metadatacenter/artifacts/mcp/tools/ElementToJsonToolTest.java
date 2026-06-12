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
 * Tests for the {@code element_to_json} tool. Mirrors {@link TemplateToJsonToolTest}'s
 * shape: a YAML input compiles to a CEDAR JSON Schema that
 * {@link CedarValidator#validateTemplateElement} accepts.
 */
final class ElementToJsonToolTest
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

    McpSchema.CallToolResult result = invoke(Map.of("artifact", yaml));

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

  @Test void mints_top_level_id_but_not_nested_child_ids() throws Exception
  {
    // A standalone element gets a minted template-elements IRI; its nested fields stay
    // id-less (DESIGN.md Principle 10 — top-level minting only).
    String yaml =
        "type: element\n"
            + "name: Address\n"
            + "children:\n"
            + "  - key: street\n"
            + "    type: text-field\n"
            + "    name: Street\n";

    McpSchema.CallToolResult result = invoke(Map.of("artifact", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    MintedIds.assertMintedId(rendered.get("@id"), "template-elements");
    MintedIds.assertNoId(rendered.path("properties").path("street").path("@id"),
        "nested field 'street'");
  }

  @Test void preserves_supplied_id() throws Exception
  {
    String id = "https://repo.metadatacenter.org/template-elements/abc-123";
    String yaml =
        "type: element\n"
            + "name: Address\n"
            + "id: " + id + "\n";

    McpSchema.CallToolResult result = invoke(Map.of("artifact", yaml));

    assertFalse(result.isError(), errorText(result));
    assertEquals(id, parseJson(result).get("@id").asText(),
        "a supplied id must be preserved, not overwritten by minting");
  }

  @Test void rejects_yaml_whose_top_level_type_is_template()
  {
    String yaml =
        "type: template\n"
            + "name: NotAnElement\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("artifact", yaml));

    assertTrue(result.isError(),
        "type: template must not compile via element_to_json; got: " + result);
  }

  @Test void rejects_missing_artifact_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("artifact"));
  }

  @Test void rejects_blank_yaml()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "   \n  \n"));
    assertTrue(result.isError(), "blank yaml input must produce isError=true");
  }

  @Test void rejects_malformed_yaml_with_clean_error()
  {
    String yaml = "type: element\n\tname: malformed\n";

    McpSchema.CallToolResult result = invoke(Map.of("artifact", yaml));

    assertTrue(result.isError(), "malformed yaml must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("yaml"),
        "error should identify yaml as the problem area; got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return ElementToJsonTool.handler(null,
        new McpSchema.CallToolRequest("element_to_json", arguments));
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

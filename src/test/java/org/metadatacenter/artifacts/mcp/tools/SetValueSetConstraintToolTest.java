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

final class SetValueSetConstraintToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void adds_valueset_constraint_to_controlled_term_field() throws Exception
  {
    String fieldJson = createControlledTermField("Area unit");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "value_set_iri", "https://example.org/cedarvs/area-units",
        "vs_collection", "CEDARVS",
        "name", "Area units"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode valueSets = rendered.path("_valueConstraints").path("valueSets");
    assertTrue(valueSets.isArray() && valueSets.size() == 1,
        "_valueConstraints.valueSets must carry one entry; got: "
            + rendered.path("_valueConstraints"));
    assertEquals("CEDARVS", valueSets.get(0).path("vsCollection").asText());

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus(),
        "constrained field must pass validateTemplateField");
  }

  @Test void rejects_non_textfield_shape()
  {
    String numericFieldJson = createField("Count", "numeric-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", numericFieldJson,
        "value_set_iri", "https://example.org/vs",
        "vs_collection", "CEDARVS",
        "name", "X"));
    assertTrue(result.isError());
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetValueSetConstraintTool.handler(null,
        new McpSchema.CallToolRequest("set_valueset_constraint", args));
  }

  private String createControlledTermField(String name) { return createField(name, "controlled-term-field"); }

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

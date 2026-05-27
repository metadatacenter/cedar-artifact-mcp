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

final class AddIriDefaultValueToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void sets_ror_default() throws Exception
  {
    String fieldJson = createField("Affiliation", "ext-ror-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "iri", "https://ror.org/00f54p054"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("https://ror.org/00f54p054",
        rendered.path("_valueConstraints").path("defaultValue").asText(),
        "default IRI must appear under _valueConstraints; got: "
            + rendered.path("_valueConstraints"));

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void rejects_text_field()
  {
    String fieldJson = createField("Note", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "iri", "https://example.org/x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("iri field")
            || errorText(result).contains("IRI field"),
        "error should explain the type mismatch; got: " + errorText(result));
  }

  @Test void rejects_invalid_iri()
  {
    String fieldJson = createField("ROR", "ext-ror-field");
    McpSchema.CallToolResult result = invoke(Map.of(
        "field_json", fieldJson,
        "iri", "not a uri with spaces"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("iri"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return AddIriDefaultValueTool.handler(null,
        new McpSchema.CallToolRequest("add_iri_default_value", args));
  }

  private static String createField(String name, String type)
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

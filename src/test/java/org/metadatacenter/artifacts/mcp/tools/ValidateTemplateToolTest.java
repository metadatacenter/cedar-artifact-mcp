package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code validate_template}. Validation runs CedarValidator on the artifact and returns
 * a {@code {"valid": ...}} report — the tool call itself succeeds either way (the verdict is the
 * payload). Invalid/junk input is the headline case: it is what arrives from the wild.
 */
final class ValidateTemplateToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void valid_template_reports_valid() throws Exception
  {
    String templateJson = templateToJson(createTemplate("Demographics"));

    McpSchema.CallToolResult result = invoke(Map.of("artifact", templateJson));

    assertFalse(result.isError(), errorText(result));
    assertTrue(report(result).path("valid").asBoolean(),
        "a canonical CEDAR template JSON must validate; got:\n" + textOf(result));
  }

  @Test void valid_template_yaml_reports_valid() throws Exception
  {
    // YAML input is read through the library, then validated — must reach the same verdict.
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createTemplate("Demographics")));

    assertFalse(result.isError(), errorText(result));
    assertTrue(report(result).path("valid").asBoolean(),
        "a template authored as YAML must validate; got:\n" + textOf(result));
  }

  @Test void invalid_template_reports_errors() throws Exception
  {
    // A wild artifact that claims to be a template but is missing every required property:
    // the validator must say so, as a {"valid": false} report (not a tool error).
    String junk = "{ \"@type\": \"https://schema.metadatacenter.org/core/Template\" }";

    McpSchema.CallToolResult result = invoke(Map.of("artifact", junk));

    assertFalse(result.isError(), "an invalid artifact is still a successful tool call: " + errorText(result));
    JsonNode report = report(result);
    assertFalse(report.path("valid").asBoolean(), "junk template must not validate; got:\n" + report);
    assertTrue(report.path("errors").isArray() && report.path("errors").size() > 0,
        "an invalid result must carry diagnostics; got:\n" + report);
  }

  @Test void rejects_kind_mismatch_with_redirect() throws Exception
  {
    // Handed a field, validate_template should redirect rather than emit a confusing schema error.
    String fieldJson = fieldToJson(createField("Patient name", "text-field"));

    McpSchema.CallToolResult result = invoke(Map.of("artifact", fieldJson));

    assertTrue(result.isError(), "a field passed to validate_template must be redirected");
    assertTrue(errorText(result).contains("validate_field"),
        "redirect should name validate_field; got: " + errorText(result));
  }

  @Test void rejects_missing_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("artifact"));
  }

  @Test void rejects_blank_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "   "));
    assertTrue(result.isError());
  }

  @Test void rejects_unparseable_artifact()
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "{ not json"));
    assertTrue(result.isError(), "unparseable input must be a tool error");
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ValidateTemplateTool.handler(null, new McpSchema.CallToolRequest("validate_template", args));
  }

  private static String createTemplate(String name)
  {
    return textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name))));
  }

  private static String createField(String name, String type)
  {
    return textOf(CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type))));
  }

  private static String templateToJson(String yaml)
  {
    return textOf(TemplateToJsonTool.handler(null,
        new McpSchema.CallToolRequest("template_to_json", Map.of("artifact", yaml))));
  }

  private static String fieldToJson(String yaml)
  {
    return textOf(FieldToJsonTool.handler(null,
        new McpSchema.CallToolRequest("field_to_json", Map.of("artifact", yaml))));
  }

  private JsonNode report(McpSchema.CallToolResult result) throws Exception
  {
    return jackson.readTree(textOf(result));
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

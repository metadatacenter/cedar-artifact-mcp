package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidateInstanceToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void minimal_instance_passes_validation_against_empty_template() throws Exception
  {
    // The smallest end-to-end: an empty template (just @context/@id/schema:name/etc.)
    // and a matching minimal instance. The instance carries the required JSON-LD
    // skeleton; the template has no child properties to enforce.
    String templateJson = createTemplate("Demographics");
    // Build the matching instance from the same template; isBasedOn derives from its @id.
    String instanceJson = createInstance(templateJson, "Patient 42", "One patient record");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", instanceJson));

    assertFalse(result.isError(), errorText(result));
    JsonNode report = jackson.readTree(textOf(result));
    assertTrue(report.path("valid").asBoolean(),
        "minimal instance must validate cleanly; got report:\n" + report.toPrettyString());
  }

  @Test void invalid_instance_reports_errors() throws Exception
  {
    // Hand-craft a JSON object that's obviously not a valid CEDAR instance — missing
    // every required field. Validation must fail with a structured errors list.
    String templateJson = createTemplate("Demographics");
    String junkInstance = "{ \"@context\": {} }";

    McpSchema.CallToolResult result = invoke(Map.of(
        "template", templateJson,
        "instance", junkInstance));

    assertFalse(result.isError(),
        "validation report itself is a successful tool call; got: " + errorText(result));
    JsonNode report = jackson.readTree(textOf(result));
    assertFalse(report.path("valid").asBoolean(),
        "junk instance must not validate; got report:\n" + report.toPrettyString());
    assertTrue(report.path("errors").isArray() && report.path("errors").size() > 0,
        "invalid result must carry an errors array; got: " + report.toPrettyString());
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("template"));
  }

  @Test void rejects_malformed_template()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", "{ not json",
        "instance", "{}"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("template"));
  }

  @Test void rejects_malformed_instance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template", createTemplate("X"),
        "instance", "{ not json"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("instance"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ValidateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance", args));
  }

  private static String createTemplate(String name)
  {
    McpSchema.CallToolResult result = CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String createInstance(
      String templateJson, String name, String description)
  {
    McpSchema.CallToolResult result = CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", Map.of(
            "template", templateJson,
            "name", name,
            "description", description)));
    assertFalse(result.isError(),
        "fixture instance must build cleanly; got: " + errorText(result));
    return textOf(result);
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

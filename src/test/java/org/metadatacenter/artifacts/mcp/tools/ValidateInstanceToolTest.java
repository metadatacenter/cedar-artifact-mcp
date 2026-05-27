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
    // Template's required[] includes schema:description, which the instance renderer only
    // emits when description.isPresent() — so the YAML must provide one explicitly here.
    String instanceJson = compileInstance(
        "type: instance\n"
            + "name: Patient 42\n"
            + "description: One patient record\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/abc\n");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson));

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
        "template_json", templateJson,
        "instance_json", junkInstance));

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
    assertTrue(errorText(result).contains("template_json"));
  }

  @Test void rejects_malformed_template_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", "{ not json",
        "instance_json", "{}"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("template_json"));
  }

  @Test void rejects_malformed_instance_json()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", createTemplate("X"),
        "instance_json", "{ not json"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("instance_json"));
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

  private static String compileInstance(String yaml)
  {
    McpSchema.CallToolResult result = InstanceFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("instance_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture instance must compile cleanly; got: " + errorText(result));
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

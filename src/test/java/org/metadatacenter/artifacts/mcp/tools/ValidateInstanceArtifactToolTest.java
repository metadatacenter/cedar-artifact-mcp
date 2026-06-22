package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code validate_instance_artifact} — the unified instance validator. It auto-detects
 * whether the schema is a template or element from its {@code @type} and runs the matching
 * CedarValidator check; an element schema validates its instance in the nested shape (standalone
 * identity keys dropped). The tool call succeeds either way — the verdict is the payload.
 */
final class ValidateInstanceArtifactToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  // -----------------------------------------------------------------
  // template-instance path
  // -----------------------------------------------------------------

  @Test void minimal_template_instance_passes() throws Exception
  {
    String template = createTemplate("Demographics");
    String instance = createInstance(template, "Patient 42", "One patient record");

    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", template, "instance_artifact", instance));

    assertFalse(result.isError(), errorText(result));
    JsonNode report = jackson.readTree(textOf(result));
    assertTrue(report.path("valid").asBoolean(),
        "minimal instance must validate cleanly; got report:\n" + report.toPrettyString());
  }

  @Test void invalid_template_instance_reports_errors() throws Exception
  {
    // A JSON object that's obviously not a valid CEDAR instance — missing every required field.
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", createTemplate("Demographics"),
        "instance_artifact", "{ \"@context\": {} }"));

    assertFalse(result.isError(),
        "validation report itself is a successful tool call; got: " + errorText(result));
    JsonNode report = jackson.readTree(textOf(result));
    assertFalse(report.path("valid").asBoolean(),
        "junk instance must not validate; got report:\n" + report.toPrettyString());
    assertTrue(report.path("errors").isArray() && report.path("errors").size() > 0,
        "invalid result must carry an errors array; got: " + report.toPrettyString());
  }

  // -----------------------------------------------------------------
  // element-instance path
  // -----------------------------------------------------------------

  @Test void a_fresh_element_skeleton_is_valid()
  {
    String element = addressElement();
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", element)));

    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", element, "instance_artifact", entry));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : true"),
        "an empty skeleton must validate; got: " + textOf(result));
  }

  @Test void a_misshapen_element_instance_is_reported_invalid_not_a_tool_error()
  {
    // An @context of the wrong JSON type can't be read as an element instance, so the tool
    // validates the raw form — and the verdict is a report, not a tool error.
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", addressElement(),
        "instance_artifact", "{\"@context\": 42, \"street\": {\"@value\": \"x\"}}"));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : false"),
        "a misshapen element instance must yield an invalid report; got: " + textOf(result));
  }

  @Test void an_unknown_element_child_is_reported_invalid()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", addressElement(),
        "instance_artifact", "{\"@context\": {}, \"bogus\": {\"@value\": \"x\"}}"));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : false"),
        "a child the element doesn't declare must be invalid; got: " + textOf(result));
  }

  // -----------------------------------------------------------------
  // dispatch + argument handling
  // -----------------------------------------------------------------

  @Test void rejects_schema_that_is_neither_template_nor_element() throws Exception
  {
    String fieldJson = textOf(RenderSchemaArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_schema_artifact", Map.of("schema_artifact", createField("Name", "text-field"), "format", "json"))));

    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", fieldJson, "instance_artifact", "{}"));

    assertTrue(result.isError(), "a field is not a schema an instance can be based on");
    assertTrue(errorText(result).contains("template or element"),
        "error should say a template or element is required; got: " + errorText(result));
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("schema_artifact"));
  }

  @Test void rejects_malformed_schema()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", ":::not yaml or json:::",
        "instance_artifact", "{}"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("schema_artifact"));
  }

  @Test void rejects_malformed_instance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "schema_artifact", createTemplate("X"),
        "instance_artifact", "{ not json"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("instance_artifact"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ValidateInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance_artifact", args));
  }

  private static String createTemplate(String name)
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", name)));
  }

  private static String createField(String name, String type)
  {
    return textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", name, "type", type)));
  }

  private static String createInstance(String template, String name, String description)
  {
    return textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template, "name", name, "description", description)));
  }

  /** An element named Address carrying one text field, street. */
  private static String addressElement()
  {
    Map<String, Object> fieldArgs = new LinkedHashMap<>();
    fieldArgs.put("name", "Street");
    fieldArgs.put("type", "text-field");
    String street = textOf(invokeTool(CreateFieldTool::handler, "create_field", fieldArgs));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element",
            Map.of("name", "Address"))),
        "child", street,
        "key", "street")));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(McpSyncServerExchange e, McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture step '" + name + "' must succeed; got: " + errorText(result));
    return result;
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

package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code validate_element_instance} — an element artifact is itself the JSON
 * Schema its instances validate against, so the tool runs the canonical
 * CedarValidator.validateTemplateInstance with the element as the schema document. The
 * sub-record is checked in its nested shape (standalone identity keys dropped).
 */
final class ValidateElementInstanceToolTest
{
  @Test void a_fresh_skeleton_is_valid()
  {
    String element = addressElement();
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", element)));

    McpSchema.CallToolResult result = invoke(Map.of(
        "element", element, "element_instance", entry));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : true"),
        "an empty skeleton must validate; got: " + textOf(result));
  }

  @Test void a_misshapen_sub_record_is_reported_invalid_not_a_tool_error()
  {
    // An @context of the wrong JSON type can't be read as an element instance, so the
    // tool validates the raw form — and the verdict is a report, not a tool error.
    McpSchema.CallToolResult result = invoke(Map.of(
        "element", addressElement(),
        "element_instance", "{\"@context\": 42, \"street\": {\"@value\": \"x\"}}"));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : false"),
        "a misshapen sub-record must yield an invalid report; got: " + textOf(result));
  }

  @Test void an_unknown_child_is_reported_invalid()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "element", addressElement(),
        "element_instance", "{\"@context\": {}, \"bogus\": {\"@value\": \"x\"}}"));

    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("\"valid\" : false"),
        "a child the element doesn't declare must be invalid; got: " + textOf(result));
  }

  @Test void rejects_garbage_element()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "element", ":::not yaml or json:::",
        "element_instance", "{}"));

    assertTrue(result.isError());
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ValidateElementInstanceTool.handler(null,
        new McpSchema.CallToolRequest("validate_element_instance", args));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(io.modelcontextprotocol.server.McpSyncServerExchange e,
        McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture step '" + name + "' must succeed; got: " + errorText(result));
    return result;
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

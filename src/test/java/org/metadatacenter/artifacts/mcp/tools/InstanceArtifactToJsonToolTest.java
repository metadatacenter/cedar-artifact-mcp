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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code instance_artifact_to_json} — exports a template instance or an element instance
 * (auto-detected) to CEDAR JSON, optionally inflating the sparse instance against the
 * schema it is based on.
 */
final class InstanceArtifactToJsonToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void exports_a_template_instance() throws Exception
  {
    String template = templateWithField();
    String instance = textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template)));

    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", instance));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).has("schema:isBasedOn"),
        "a template instance JSON must carry schema:isBasedOn; got:\n" + textOf(result));
  }

  @Test void inflates_a_template_instance_against_its_template() throws Exception
  {
    String template = templateWithField();
    String instance = textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template)));

    JsonNode sparse = jackson.readTree(textOf(invoke(Map.of("instance_artifact", instance))));
    JsonNode inflated = jackson.readTree(textOf(invoke(Map.of(
        "instance_artifact", instance, "schema_artifact", template))));

    assertFalse(sparse.has("Weight"), "without the template the sparse instance omits the field");
    assertTrue(inflated.has("Weight"),
        "supplying the template must inflate the empty field slot; got:\n" + inflated.toPrettyString());
  }

  @Test void exports_an_element_instance() throws Exception
  {
    // The gap this unification fills: a standalone element instance had no render path before.
    String element = addressElement();
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", element)));

    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", entry));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).has("@context"),
        "an element instance JSON must carry @context; got:\n" + textOf(result));
  }

  @Test void inflates_an_element_instance_against_its_element() throws Exception
  {
    String element = addressElement();
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", element)));

    JsonNode inflated = jackson.readTree(textOf(invoke(Map.of(
        "instance_artifact", entry, "schema_artifact", element))));
    assertTrue(inflated.has("Street"),
        "supplying the element must inflate the empty Street slot; got:\n" + inflated.toPrettyString());
  }

  @Test void rejects_missing_instance()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("instance_artifact"));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return InstanceArtifactToJsonTool.handler(null,
        new McpSchema.CallToolRequest("instance_artifact_to_json", args));
  }

  /** A template carrying one numeric field, Weight. */
  private static String templateWithField()
  {
    Map<String, Object> fieldArgs = new LinkedHashMap<>();
    fieldArgs.put("name", "Weight");
    fieldArgs.put("type", "numeric-field");
    String weight = textOf(invokeTool(CreateFieldTool::handler, "create_field", fieldArgs));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateTemplateTool::handler, "create_template",
            Map.of("name", "Vitals"))),
        "child", weight,
        "key", "Weight")));
  }

  /** An element named Address carrying one text field, Street. */
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
        "key", "Street")));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(McpSyncServerExchange e, McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture '" + name + "' must succeed; got: " + errorText(result));
    return result;
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

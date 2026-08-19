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
 * Tests for {@code render_instance_artifact} — renders a template instance or an element instance
 * (auto-detected) to YAML (default) or JSON, optionally inflating the sparse instance against the
 * template/element it is based on. No validation runs. Schema artifacts are redirected to
 * {@code render_schema_artifact}.
 */
final class RenderInstanceArtifactToolTest
{

  /** Stands in for a template a repository has stored: only such a template can be based on. */
  private static final String STORED_TEMPLATE_IRI =
      "https://repo.metadatacenter.org/templates/f0c1a2b3-4d5e-6f70-8192-a3b4c5d6e7f8";
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void renders_yaml_by_default() throws Exception
  {
    String instance = templateInstance();
    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", instance));
    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertFalse(yaml.stripLeading().startsWith("{"), "default output must be YAML; got:\n" + yaml);
    assertTrue(yaml.contains("type: instance"),
        "a template instance must render with the instance discriminator; got:\n" + yaml);
  }

  @Test void renders_json_when_format_json() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "instance_artifact", templateInstance(), "format", "json"));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).has("schema:isBasedOn"),
        "a template instance JSON must carry schema:isBasedOn; got:\n" + textOf(result));
  }

  @Test void inflates_a_template_instance_when_template_supplied() throws Exception
  {
    String template = templateWithField();
    String instance = textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template)));

    JsonNode sparse = jackson.readTree(textOf(invoke(Map.of(
        "instance_artifact", instance, "format", "json"))));
    JsonNode inflated = jackson.readTree(textOf(invoke(Map.of(
        "instance_artifact", instance, "template_artifact", template, "format", "json"))));

    assertFalse(sparse.has("Weight"), "without the template the sparse instance omits the field");
    assertTrue(inflated.has("Weight"),
        "supplying template_artifact must inflate the empty field slot; got:\n" + inflated.toPrettyString());
  }

  @Test void inflates_an_element_instance_when_element_supplied() throws Exception
  {
    String element = addressElement();
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", element)));

    JsonNode inflated = jackson.readTree(textOf(invoke(Map.of(
        "instance_artifact", entry, "template_artifact", element, "format", "json"))));
    assertTrue(inflated.has("Street"),
        "supplying template_artifact must inflate the empty Street slot; got:\n" + inflated.toPrettyString());
  }

  @Test void compact_with_json_is_an_error()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "instance_artifact", templateInstance(), "format", "json", "compact", true));
    assertTrue(result.isError(), "compact + json must be a tool error");
    assertTrue(errorText(result).toLowerCase().contains("compact"),
        "the error should mention compact; got: " + errorText(result));
  }

  @Test void compact_true_yaml_is_leaner_than_expanded() throws Exception
  {
    String instance = templateInstance();
    String expanded = textOf(invoke(Map.of("instance_artifact", instance, "compact", false)));
    String compact = textOf(invoke(Map.of("instance_artifact", instance, "compact", true)));
    assertTrue(compact.length() <= expanded.length(),
        "compact YAML should be no longer than expanded; compact:\n" + compact + "\nexpanded:\n" + expanded);
  }

  @Test void redirects_a_schema_artifact() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "instance_artifact", createField("Patient name", "text-field")));
    assertTrue(result.isError(), "a field is a schema artifact, not an instance");
    assertTrue(errorText(result).contains("render_schema_artifact"),
        "redirect should name render_schema_artifact; got: " + errorText(result));
  }

  @Test void renders_no_top_level_id_when_the_instance_names_none() throws Exception
  {
    // A hand-authored sparse element instance with no id stays without one: CEDAR assigns identity.
    String entry = "type: element-instance\nname: Address\n";
    McpSchema.CallToolResult result = invoke(Map.of("instance_artifact", entry, "format", "json"));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("@id").isNull()
            || jackson.readTree(textOf(result)).path("@id").isMissingNode(),
        "rendering invents no identity; got:\n" + textOf(result));
  }

  @Test void rejects_missing_instance()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("instance_artifact"));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return RenderInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_instance_artifact", args));
  }

  /** A template instance built from a template carrying one numeric field. */
  private static String templateInstance()
  {
    return textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", templateWithField())));
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
            Map.of("name", "Vitals", "id", STORED_TEMPLATE_IRI))),
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

  private static String createField(String name, String type)
  {
    return textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", name, "type", type)));
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

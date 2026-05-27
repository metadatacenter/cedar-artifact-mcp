package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SetFieldValueToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void sets_text_field_value_at_top_level() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Patient\n"
            + "description: Patient template\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n");
    String instanceJson = createInstance(templateJson, "Patient 42");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "patient_name",
        "value", "Alice"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("Alice", rendered.path("patient_name").path("@value").asText(),
        "patient_name's @value must equal the supplied string");
  }

  @Test void sets_numeric_field_value() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Patient\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: age\n"
            + "    type: numeric-field\n"
            + "    name: Age\n");
    String instanceJson = createInstance(templateJson, "Patient");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "age",
        "value", 42));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    // The library renders the @value as a string. The important thing is round-trip
    // correctness — the value is preserved.
    assertEquals("42", rendered.path("age").path("@value").asText());
  }

  @Test void sets_field_value_inside_nested_element() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: With address\n"
            + "description: Nested\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: address\n"
            + "    type: element\n"
            + "    name: Address\n"
            + "    description: Postal address\n"
            + "    modelVersion: 1.6.0\n"
            + "    children:\n"
            + "      - key: street\n"
            + "        type: text-field\n"
            + "        name: Street\n");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "address/street",
        "value", "221B Baker St"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("221B Baker St",
        rendered.path("address").path("street").path("@value").asText(),
        "nested field value must be set; got address:\n" + rendered.path("address"));
  }

  @Test void rejects_path_to_iri_field()
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: P\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: ror\n"
            + "    type: ext-ror-field\n"
            + "    name: ROR\n");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "ror",
        "value", "https://ror.org/x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("set_iri_field_value"),
        "error should redirect to set_iri_field_value; got: " + errorText(result));
  }

  @Test void rejects_unknown_field_path()
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: P\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: a\n"
            + "    type: text-field\n"
            + "    name: A\n");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "nonexistent",
        "value", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nonexistent"));
  }

  @Test void rejects_missing_value()
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: P\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n");
    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", "{}",
        "field_path", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("value"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return SetFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("set_field_value", arguments));
  }

  private static String compileTemplate(String yaml)
  {
    McpSchema.CallToolResult result = TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture template YAML must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String createInstance(String templateJson, String name)
  {
    McpSchema.CallToolResult result = CreateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_instance", Map.of(
            "template_json", templateJson,
            "is_based_on", FAKE_BASED_ON,
            "name", name)));
    assertFalse(result.isError(),
        "fixture instance must build cleanly; got: " + errorText(result));
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

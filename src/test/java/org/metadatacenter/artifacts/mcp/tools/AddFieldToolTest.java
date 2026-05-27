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

/**
 * Tests for the {@code add_field} tool. Parallels {@link AddElementToolTest}: both
 * tools take a pre-built child JSON, dispatch on parent {@code @type}, and return
 * the updated parent JSON revalidated by CedarValidator.
 */
final class AddFieldToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void adds_field_to_template_parent() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String fieldJson = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "patient_name"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode child = rendered.path("properties").path("patient_name");
    assertTrue(child.isObject(),
        "field must appear under properties.<key>; got: " + rendered.path("properties"));

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated template must pass validateTemplate");
  }

  @Test void adds_field_to_element_parent() throws Exception
  {
    String elementJson = createElement("Address");
    String fieldJson = createField("Country", "controlled-term-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", elementJson,
        "child_json", fieldJson,
        "key", "country"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("properties").path("country").isObject(),
        "country must appear under the element's properties");

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    assertEquals("true", report.getValidationStatus(),
        "updated element must pass validateTemplateElement");
  }

  @Test void name_override_appears_in_propertyLabels() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String fieldJson = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "patient_full_name",
        "name", "Patient full name"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Patient full name",
        rendered.path("_ui").path("propertyLabels").path("patient_full_name").asText(),
        "name override must surface in _ui.propertyLabels; got _ui: " + rendered.path("_ui"));
  }

  @Test void isMultiInstance_true_renders_field_as_array() throws Exception
  {
    // CEDAR templates render multi-instance fields as a JSON Schema array of objects;
    // single-instance fields render as a bare object. The isMultiInstance flag is the
    // per-add-site control over which shape the parent gets.
    String templateJson = createTemplate("Multi");
    String fieldJson = createField("Email", "email-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "emails",
        "isMultiInstance", true));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("array", rendered.path("properties").path("emails").path("type").asText(),
        "multi-instance field must render as an array; got: "
            + rendered.path("properties").path("emails"));
  }

  @Test void isMultiInstance_default_false_renders_field_as_object() throws Exception
  {
    String templateJson = createTemplate("Single");
    String fieldJson = createField("Email", "email-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "email"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("object", rendered.path("properties").path("email").path("type").asText(),
        "default (isMultiInstance unset) must render as a bare object");
  }

  @Test void description_override_appears_in_propertyDescriptions() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String fieldJson = createField("Patient name", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "patient_name",
        "description", "Override description"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Override description",
        rendered.path("_ui").path("propertyDescriptions").path("patient_name").asText(),
        "description override must surface in _ui.propertyDescriptions");
  }

  @Test void minItems_and_maxItems_apply_to_multi_instance_field() throws Exception
  {
    String templateJson = createTemplate("Bounded");
    String fieldJson = createField("Tag", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson,
        "key", "tags",
        "isMultiInstance", true,
        "minItems", 1,
        "maxItems", 5));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    JsonNode tags = rendered.path("properties").path("tags");
    assertEquals("array", tags.path("type").asText(),
        "multi-instance field must render as an array");
    assertEquals(1, tags.path("minItems").asInt(),
        "minItems must surface on the array wrapper; got: " + tags);
    assertEquals(5, tags.path("maxItems").asInt(),
        "maxItems must surface on the array wrapper; got: " + tags);
  }

  @Test void rejects_non_integer_minItems()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", createTemplate("X"),
        "child_json", createField("X", "text-field"),
        "key", "x",
        "minItems", "two"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("minItems"));
  }

  @Test void rejects_non_boolean_isMultiInstance()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", createTemplate("X"),
        "child_json", createField("X", "text-field"),
        "key", "x",
        "isMultiInstance", "yes"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("isMultiInstance"));
  }

  @Test void key_defaults_to_childs_schema_name() throws Exception
  {
    String templateJson = createTemplate("Demographics");
    String fieldJson = createField("patient_email", "email-field");

    // No 'key' arg — should fall back to child's schema:name ("patient_email").
    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertTrue(rendered.path("properties").path("patient_email").isObject(),
        "field should appear under the default key (child's schema:name); got: "
            + rendered.path("properties"));
  }

  @Test void rejects_duplicate_default_key() throws Exception
  {
    // Adding the same-named child twice with no explicit key surfaces the library's
    // duplicate-child guard — the second add must fail rather than silently overwriting.
    String templateJson = createTemplate("Dup");
    String fieldJson = createField("contact", "text-field");

    McpSchema.CallToolResult first = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", fieldJson));
    assertFalse(first.isError(), errorText(first));

    McpSchema.CallToolResult second = invoke(Map.of(
        "parent_json", textOf(first),
        "child_json", fieldJson));
    assertTrue(second.isError(),
        "duplicate key (default) must produce isError=true; got: " + second);
    assertTrue(errorText(second).toLowerCase().contains("contact"),
        "error should mention the conflicting key; got: " + errorText(second));
  }

  @Test void rejects_child_json_that_is_not_a_field()
  {
    // An element JSON must not be accepted as a field child — that's add_element's job.
    String templateJson = createTemplate("X");
    String elementJson = createElement("not-a-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", templateJson,
        "child_json", elementJson,
        "key", "x"));
    assertTrue(result.isError(),
        "an element JSON must not be accepted as a field child; got: " + result);
  }

  @Test void rejects_parent_without_at_type()
  {
    String fieldJson = createField("X", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", "{}",
        "child_json", fieldJson,
        "key", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("@type"));
  }

  @Test void rejects_parent_with_field_at_type()
  {
    // A bare field is a valid CEDAR artifact but isn't a parent — add_field must refuse it.
    String fieldJson = createField("standalone", "text-field");
    String anotherFieldJson = createField("another", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "parent_json", fieldJson,
        "child_json", anotherFieldJson,
        "key", "x"));
    assertTrue(result.isError(),
        "field artifact must not be accepted as a parent; got: " + result);
  }

  @Test void rejects_missing_required_args()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("parent_json"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", arguments));
  }

  private String createTemplate(String name)
  {
    McpSchema.CallToolResult result = CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture template must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private String createElement(String name)
  {
    McpSchema.CallToolResult result = CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", Map.of("name", name)));
    assertFalse(result.isError(),
        "fixture element must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

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
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

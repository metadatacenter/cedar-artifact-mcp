package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code set_attribute_value} / {@code unset_attribute_value} — populating the dynamic
 * name→value entries of an attribute-value field on a template instance.
 */
final class AttributeValueToolsTest
{
  @Test void adds_an_attribute_entry()
  {
    String template = templateWithAttributeValueField();
    String instance = instanceOf(template);

    McpSchema.CallToolResult result = set(template, instance, "Custom Properties", "color", "red");
    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("color"), "attribute name must be present; got:\n" + yaml);
    assertTrue(yaml.contains("red"), "attribute value must be present; got:\n" + yaml);
  }

  @Test void overwrites_an_existing_attribute()
  {
    String template = templateWithAttributeValueField();
    String once = textOf(set(template, instanceOf(template), "Custom Properties", "color", "red"));
    McpSchema.CallToolResult twice = set(template, once, "Custom Properties", "color", "blue");
    assertFalse(twice.isError(), errorText(twice));
    assertTrue(textOf(twice).contains("blue"), "overwrite must keep the new value; got:\n" + textOf(twice));
    assertFalse(textOf(twice).contains("red"), "overwrite must drop the old value; got:\n" + textOf(twice));
  }

  @Test void result_validates_against_the_template()
  {
    String template = templateWithAttributeValueField();
    String withAttr = textOf(set(template, instanceOf(template), "Custom Properties", "color", "red"));

    McpSchema.CallToolResult validation = ValidateInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance_artifact",
            Map.of("schema_artifact", template, "instance_artifact", withAttr)));
    assertFalse(validation.isError(), errorText(validation));
    assertTrue(textOf(validation).contains("\"valid\" : true"),
        "an instance with an attribute-value entry must validate; got:\n" + textOf(validation));
  }

  @Test void removes_an_attribute_entry()
  {
    String template = templateWithAttributeValueField();
    String withAttr = textOf(set(template, instanceOf(template), "Custom Properties", "color", "gone soon"));
    McpSchema.CallToolResult removed = unset(template, withAttr, "Custom Properties", "color");
    assertFalse(removed.isError(), errorText(removed));
    assertFalse(textOf(removed).contains("gone soon"),
        "removed attribute value must be absent; got:\n" + textOf(removed));
  }

  @Test void unset_is_idempotent()
  {
    String template = templateWithAttributeValueField();
    McpSchema.CallToolResult result = unset(template, instanceOf(template), "Custom Properties", "never-there");
    assertFalse(result.isError(), "removing an absent attribute must succeed: " + errorText(result));
  }

  @Test void rejects_a_non_attribute_value_field()
  {
    // Target a plain text field — set_attribute_value must refuse and point at the value setters.
    String field = textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", "Name", "type", "text-field")));
    String template = textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", "Study"))),
        "child", field, "key", "Name")));

    McpSchema.CallToolResult result = set(template, instanceOf(template), "Name", "color", "red");
    assertTrue(result.isError(), "a regular field is not an attribute-value field");
    assertTrue(errorText(result).contains("not an attribute-value field"),
        "error should explain the field kind; got: " + errorText(result));
  }

  @Test void rejects_missing_value()
  {
    String template = templateWithAttributeValueField();
    McpSchema.CallToolResult result = SetAttributeValueTool.handler(null,
        new McpSchema.CallToolRequest("set_attribute_value", Map.of(
            "template", template, "instance", instanceOf(template),
            "field_path", "Custom Properties", "attribute_name", "color")));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("value"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult set(String template, String instance, String fieldPath,
      String attributeName, String value)
  {
    return SetAttributeValueTool.handler(null, new McpSchema.CallToolRequest("set_attribute_value", Map.of(
        "template", template, "instance", instance, "field_path", fieldPath,
        "attribute_name", attributeName, "value", value)));
  }

  private static McpSchema.CallToolResult unset(String template, String instance, String fieldPath,
      String attributeName)
  {
    return UnsetAttributeValueTool.handler(null, new McpSchema.CallToolRequest("unset_attribute_value", Map.of(
        "template", template, "instance", instance, "field_path", fieldPath, "attribute_name", attributeName)));
  }

  /** A template carrying one attribute-value field, "Custom Properties". */
  private static String templateWithAttributeValueField()
  {
    String av = textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", "Custom Properties", "type", "attribute-value-field")));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", "Study"))),
        "child", av, "key", "Custom Properties")));
  }

  private static String instanceOf(String template)
  {
    return textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", template)));
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code validate_artifact} — the auto-detecting validator. It detects template /
 * element / field from {@code @type} and dispatches; instances are detected but redirected to
 * {@code validate_instance}.
 */
final class ValidateArtifactToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void auto_detects_and_validates_template() throws Exception
  {
    String templateJson = textOf(TemplateToJsonTool.handler(null,
        new McpSchema.CallToolRequest("template_to_json", Map.of("artifact", createTemplate("Demo")))));
    McpSchema.CallToolResult result = invoke(Map.of("artifact", templateJson));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("valid").asBoolean());
  }

  @Test void auto_detects_and_validates_field() throws Exception
  {
    String fieldJson = textOf(FieldToJsonTool.handler(null,
        new McpSchema.CallToolRequest("field_to_json", Map.of("artifact", createField("Name", "text-field")))));
    McpSchema.CallToolResult result = invoke(Map.of("artifact", fieldJson));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("valid").asBoolean());
  }

  @Test void redirects_instance_to_validate_instance() throws Exception
  {
    String instanceJson = textOf(InstanceToJsonTool.handler(null,
        new McpSchema.CallToolRequest("instance_to_json", Map.of("artifact",
            "type: instance\nname: P1\nisBasedOn: https://repo.metadatacenter.org/templates/x\n"))));

    McpSchema.CallToolResult result = invoke(Map.of("artifact", instanceJson));

    assertTrue(result.isError(), "an instance must be redirected, not validated here");
    assertTrue(errorText(result).contains("validate_instance"),
        "redirect should name validate_instance; got: " + errorText(result));
  }

  @Test void rejects_undetermined_kind()
  {
    // No @type and no isBasedOn — kind can't be determined.
    McpSchema.CallToolResult result = invoke(Map.of("artifact", "{ \"foo\": \"bar\" }"));
    assertTrue(result.isError(), "an artifact of indeterminate kind must error with guidance");
    assertTrue(errorText(result).toLowerCase().contains("kind"),
        "error should explain the kind couldn't be determined; got: " + errorText(result));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ValidateArtifactTool.handler(null, new McpSchema.CallToolRequest("validate_artifact", args));
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

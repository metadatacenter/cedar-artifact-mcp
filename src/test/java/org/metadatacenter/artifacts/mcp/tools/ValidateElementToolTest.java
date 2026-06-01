package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@code validate_element}. See {@link ValidateTemplateToolTest} for the full contract. */
final class ValidateElementToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void valid_element_json_reports_valid() throws Exception
  {
    String elementJson = textOf(ElementToJsonTool.handler(null,
        new McpSchema.CallToolRequest("element_to_json", Map.of("yaml", createElement("Address")))));

    McpSchema.CallToolResult result = invoke(Map.of("artifact", elementJson));

    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("valid").asBoolean(),
        "a canonical CEDAR element JSON must validate; got:\n" + textOf(result));
  }

  @Test void valid_element_yaml_reports_valid() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("artifact", createElement("Address")));
    assertFalse(result.isError(), errorText(result));
    assertTrue(jackson.readTree(textOf(result)).path("valid").asBoolean());
  }

  @Test void invalid_element_reports_errors() throws Exception
  {
    String junk = "{ \"@type\": \"https://schema.metadatacenter.org/core/TemplateElement\" }";
    McpSchema.CallToolResult result = invoke(Map.of("artifact", junk));
    assertFalse(result.isError(), errorText(result));
    assertFalse(jackson.readTree(textOf(result)).path("valid").asBoolean());
  }

  @Test void rejects_kind_mismatch_with_redirect()
  {
    String templateJson = textOf(TemplateToJsonTool.handler(null,
        new McpSchema.CallToolRequest("template_to_json", Map.of("yaml", createTemplate("Demo")))));
    McpSchema.CallToolResult result = invoke(Map.of("artifact", templateJson));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("validate_template"),
        "redirect should name validate_template; got: " + errorText(result));
  }

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return ValidateElementTool.handler(null, new McpSchema.CallToolRequest("validate_element", args));
  }

  private static String createElement(String name)
  {
    return textOf(CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", Map.of("name", name))));
  }

  private static String createTemplate(String name)
  {
    return textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", name))));
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

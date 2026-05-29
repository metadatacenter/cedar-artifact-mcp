package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code create_template} tool.
 *
 * <p>The headline test, {@link #createTemplate_rendersJsonThatPassesCedarValidator}, mirrors
 * the validation step from the artifact library's own renderer tests
 * ({@code JsonArtifactRendererTest#validateTemplateSchemaArtifact}): it runs the rendered
 * output through {@link CedarValidator#validateTemplate(JsonNode)} and asserts the
 * validation status is {@code "true"}. This guarantees that what the MCP tool returns is
 * not just well-formed JSON but a CEDAR template that the canonical validator accepts.
 */
final class CreateTemplateToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void createTemplate_rendersJsonThatPassesCedarValidator() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "description", "Minimal demographics template",
        "version", "0.1.0"));

    assertFalse(result.isError(), "tool should not report error for valid input");

    ObjectNode rendered = parseTemplateJson(result);
    ValidationReport report = cedarValidator.validateTemplate(rendered);

    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder failureDetail = new StringBuilder("CedarValidator rejected the rendered template:\n");
      for (ErrorItem err : report.getErrors()) {
        failureDetail.append("  - ").append(err).append('\n');
      }
      org.junit.jupiter.api.Assertions.fail(failureDetail.toString());
    }
  }

  @Test void createTemplate_setsSchemaNameAndVersion() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "version", "0.1.0"));

    ObjectNode rendered = parseTemplateJson(result);
    assertEquals("Patient demographics", rendered.get("schema:name").asText());
    assertEquals("0.1.0", rendered.get("pav:version").asText());
  }

  @Test void createTemplate_defaultsDescriptionAndVersionWhenOmitted() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "Minimal"));

    assertFalse(result.isError(), "omitting optional fields should still succeed");
    ObjectNode rendered = parseTemplateJson(result);
    assertEquals("0.0.1", rendered.get("pav:version").asText());
    assertEquals("", rendered.get("schema:description").asText());
  }

  @Test void createTemplate_rejectsBlankName()
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "   "));

    assertTrue(result.isError(), "blank name should produce an error result");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    assertTrue(text.contains("name"), "error message should mention the offending field, got: " + text);
  }

  @Test void createTemplate_rejectsInvalidVersionString()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Whatever",
        "version", "not-a-version"));

    assertTrue(result.isError(), "non-semver version should produce an error result");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    assertTrue(text.toLowerCase().contains("version"),
        "error message should mention the offending field, got: " + text);
  }

  @Test void createTemplate_setsJsonLdIdWhenAbsoluteIriSupplied() throws Exception
  {
    String id = "https://repo.metadatacenter.org/templates/abc-123";
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "id", id));

    assertFalse(result.isError(), "a valid absolute IRI id should succeed");
    ObjectNode rendered = parseTemplateJson(result);
    assertEquals(id, rendered.get("@id").asText());
  }

  @Test void createTemplate_rejectsRelativeIri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "id", "templates/abc-123"));

    assertTrue(result.isError(), "a non-absolute IRI id should produce an error result");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    assertTrue(text.toLowerCase().contains("absolute"),
        "error message should explain the id must be absolute, got: " + text);
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("create_template", arguments);
    return CreateTemplateTool.handler(null, request);
  }

  private ObjectNode parseTemplateJson(McpSchema.CallToolResult result) throws Exception
  {
    assertNotNull(result.content(), "result should contain at least one content block");
    assertFalse(result.content().isEmpty(), "result content should not be empty");
    McpSchema.TextContent text = (McpSchema.TextContent) result.content().get(0);
    JsonNode node = jackson.readTree(text.text());
    assertTrue(node.isObject(), "rendered template should be a JSON object");
    return (ObjectNode) node;
  }
}

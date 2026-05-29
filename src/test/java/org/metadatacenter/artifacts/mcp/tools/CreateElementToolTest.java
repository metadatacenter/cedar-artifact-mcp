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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the {@code create_element} tool. Mirrors {@link CreateTemplateToolTest}'s
 * shape: a happy-path build is validated by {@link CedarValidator#validateTemplateElement}.
 */
final class CreateElementToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void createElement_rendersJsonThatPassesCedarValidator() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Address",
        "description", "Postal address element",
        "version", "0.1.0"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplateElement(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the rendered element:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }

    assertEquals("Address", rendered.get("schema:name").asText());
    assertEquals("Postal address element", rendered.get("schema:description").asText());
    assertEquals("0.1.0", rendered.get("pav:version").asText());
  }

  @Test void createElement_appliesDefaultsWhenOptionalArgsOmitted() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "Bare"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Bare", rendered.get("schema:name").asText());
    assertEquals("0.0.1", rendered.get("pav:version").asText(),
        "version should default to 0.0.1");
  }

  @Test void rejects_blank_name()
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "  "));
    assertTrue(result.isError(), "blank name must produce isError=true");
    assertTrue(errorText(result).contains("name"));
  }

  @Test void rejects_missing_name()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("name"));
  }

  @Test void rejects_invalid_version()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Element",
        "version", "garbage"));
    assertTrue(result.isError(), "invalid version must produce isError=true");
    assertTrue(errorText(result).contains("version"));
  }

  @Test void createElement_setsJsonLdIdWhenAbsoluteIriSupplied() throws Exception
  {
    String id = "https://repo.metadatacenter.org/template-elements/abc-123";
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Address",
        "id", id));

    assertFalse(result.isError(), "a valid absolute IRI id should succeed");
    ObjectNode rendered = parseJson(result);
    assertEquals(id, rendered.get("@id").asText());
  }

  @Test void createElement_mintsIdWhenOmitted() throws Exception
  {
    // Auto-mint convenience (DESIGN.md Principle 10): no id supplied -> a fresh CEDAR
    // element IRI of the correct form.
    McpSchema.CallToolResult result = invoke(Map.of("name", "Address"));

    assertFalse(result.isError(), "omitting id should still succeed");
    ObjectNode rendered = parseJson(result);
    MintedIds.assertMintedId(rendered.get("@id"), "template-elements");
  }

  @Test void createElement_rejectsRelativeIri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Address",
        "id", "template-elements/abc-123"));

    assertTrue(result.isError(), "a non-absolute IRI id should produce an error result");
    assertTrue(errorText(result).toLowerCase().contains("absolute"),
        "error message should explain the id must be absolute, got: " + errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    return CreateElementTool.handler(null,
        new McpSchema.CallToolRequest("create_element", arguments));
  }

  private ObjectNode parseJson(McpSchema.CallToolResult result) throws Exception
  {
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    JsonNode node = jackson.readTree(text);
    assertTrue(node.isObject(), "result must be a JSON object; got: " + text);
    return (ObjectNode) node;
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

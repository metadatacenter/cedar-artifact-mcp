package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the {@code create_element} tool. Mirrors {@link CreateTemplateToolTest}'s
 * shape: a happy-path build is validated by {@link CedarValidator#validateTemplateElement}.
 *
 * <p>The tool now returns the element as expanded YAML (the exchange form), so the tests
 * parse the YAML output and, for the validity check, read it back to the model and
 * validate its JSON rendering.
 */
final class CreateElementToolTest
{
  private ModelValidator cedarValidator;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
  }

  @Test void createElement_rendersYamlThatPassesCedarValidator() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Address",
        "description", "Postal address element",
        "version", "0.1.0"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    assertEquals("Address", yaml.get("name"));
    assertEquals("Postal address element", yaml.get("description"));
    // The exchange form is expanded — what was set at creation survives in the returned YAML.
    assertEquals("0.1.0", String.valueOf(yaml.get("version")));

    ValidationReport report = cedarValidator.validateTemplateElement(renderJson(yaml));
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the rendered element:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }
  }

  @Test void createElement_appliesDefaultsWhenOptionalArgsOmitted() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "Bare"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    assertEquals("Bare", yaml.get("name"));
    assertEquals("0.0.1", yaml.get("version"), "version should default to 0.0.1");
    // Expanded YAML omits an empty description rather than emitting description: "".
    assertFalse(yaml.containsKey("description"),
        "empty description should be omitted from YAML; got: " + yaml);
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
    Map<String, Object> yaml = parseYaml(result);
    assertEquals(id, yaml.get("id"));
  }

  @Test void createElement_mintsIdWhenOmitted() throws Exception
  {
    // Auto-mint convenience (DESIGN.md Principle 10): no id supplied -> a fresh CEDAR
    // element IRI of the correct form.
    McpSchema.CallToolResult result = invoke(Map.of("name", "Address"));

    assertFalse(result.isError(), "omitting id should still succeed");
    Map<String, Object> yaml = parseYaml(result);
    MintedIds.assertMintedId((String) yaml.get("id"), "template-elements");
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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content(), "result must have content");
    assertFalse(result.content().isEmpty(), "result content must not be empty");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + text);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<Object, Object> e : ((Map<Object, Object>) parsed).entrySet())
      map.put(String.valueOf(e.getKey()), e.getValue());
    return map;
  }

  /** Read the YAML element map back to the model and render its JSON for validation. */
  private static ObjectNode renderJson(Map<String, Object> yaml)
  {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(yaml);
    ElementSchemaArtifact model = new YamlArtifactReader(true).readElementSchemaArtifact(map);
    return new JsonArtifactRenderer().renderElementSchemaArtifact(model);
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
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
 * Tests for the {@code create_template} tool. The tool returns the artifact as expanded YAML
 * (the exchange form — DESIGN.md Principle 8). The headline test reads that YAML back through
 * the library reader and runs the rendered JSON through {@link CedarValidator}, so a non-error
 * result is not just well-formed YAML but a CEDAR template the canonical validator accepts.
 */
final class CreateTemplateToolTest
{
  private ModelValidator cedarValidator;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
  }

  @Test void createTemplate_rendersYamlThatPassesCedarValidator() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "description", "Minimal demographics template",
        "version", "0.1.0"));

    assertFalse(result.isError(), errorText(result));
    Map<String, Object> yaml = parseYaml(result);

    assertEquals("Patient demographics", yaml.get("name"));
    assertEquals("Minimal demographics template", yaml.get("description"));
    // The exchange form is expanded: what was set at creation survives in the returned YAML.
    assertEquals("0.1.0", String.valueOf(yaml.get("version")));

    ValidationReport report = cedarValidator.validateTemplate(renderJson(yaml));
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the rendered template:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }
  }

  @Test void createTemplate_setsNameAndVersion() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "version", "0.1.0"));

    Map<String, Object> yaml = parseYaml(result);
    assertEquals("template", yaml.get("type"));
    assertEquals("Patient demographics", yaml.get("name"));
    assertEquals("0.1.0", yaml.get("version"));
  }

  @Test void createTemplate_defaultsVersionWhenOmitted() throws Exception
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "Minimal"));

    assertFalse(result.isError(), "omitting optional fields should still succeed");
    Map<String, Object> yaml = parseYaml(result);
    assertEquals("0.0.1", yaml.get("version"));
    // Even expanded YAML omits an empty description rather than emitting description: "".
    assertFalse(yaml.containsKey("description"), "empty description should not be emitted");
  }

  @Test void createTemplate_returnsExpandedExchangeYaml() throws Exception
  {
    // Mutating tools return the expanded, lossless exchange form: version, status, and
    // modelVersion all survive into the returned YAML (nothing is dropped between tools).
    Map<String, Object> yaml = parseYaml(invoke(Map.of("name", "Lean", "version", "0.2.0")));
    assertEquals("Lean", yaml.get("name"));
    assertEquals("0.2.0", String.valueOf(yaml.get("version")));
    assertEquals("draft", String.valueOf(yaml.get("status")), "status defaults to draft");
    assertTrue(yaml.containsKey("modelVersion"), "expanded form carries modelVersion; got: " + yaml);
  }

  @Test void createTemplate_acceptsPublishedStatus() throws Exception
  {
    Map<String, Object> yaml = parseYaml(invoke(Map.of("name", "Done", "status", "published")));
    assertEquals("published", String.valueOf(yaml.get("status")));
  }

  @Test void createTemplate_rejectsUnknownStatus()
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "X", "status", "retracted"));
    assertTrue(result.isError(), "unknown status should produce an error result");
    assertTrue(errorText(result).contains("status"), errorText(result));
  }

  @Test void createTemplate_rejectsBlankName()
  {
    McpSchema.CallToolResult result = invoke(Map.of("name", "   "));

    assertTrue(result.isError(), "blank name should produce an error result");
    assertTrue(errorText(result).contains("name"),
        "error message should mention the offending field, got: " + errorText(result));
  }

  @Test void createTemplate_rejectsInvalidVersionString()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Whatever",
        "version", "not-a-version"));

    assertTrue(result.isError(), "non-semver version should produce an error result");
    assertTrue(errorText(result).toLowerCase().contains("version"),
        "error message should mention the offending field, got: " + errorText(result));
  }

  @Test void createTemplate_setsJsonLdIdWhenAbsoluteIriSupplied() throws Exception
  {
    String id = "https://repo.metadatacenter.org/templates/abc-123";
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "id", id));

    assertFalse(result.isError(), "a valid absolute IRI id should succeed");
    assertEquals(id, parseYaml(result).get("id"));
  }

  @Test void createTemplate_mintsIdWhenOmitted() throws Exception
  {
    // Auto-mint convenience (DESIGN.md Principle 10): no id supplied -> a fresh CEDAR
    // template IRI of the correct form.
    McpSchema.CallToolResult result = invoke(Map.of("name", "No id supplied"));

    assertFalse(result.isError(), "omitting id should still succeed");
    MintedIds.assertMintedId((String) parseYaml(result).get("id"), "templates");
  }

  @Test void createTemplate_rejectsRelativeIri()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "name", "Patient demographics",
        "id", "templates/abc-123"));

    assertTrue(result.isError(), "a non-absolute IRI id should produce an error result");
    assertTrue(errorText(result).toLowerCase().contains("absolute"),
        "error message should explain the id must be absolute, got: " + errorText(result));
  }

  // ---------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("create_template", arguments);
    return CreateTemplateTool.handler(null, request);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseYaml(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content(), "result should contain at least one content block");
    assertFalse(result.content().isEmpty(), "result content should not be empty");
    String text = ((McpSchema.TextContent) result.content().get(0)).text();
    Object parsed = new org.yaml.snakeyaml.Yaml().load(text);
    assertTrue(parsed instanceof Map, "rendered template should be a YAML mapping; got: " + text);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<Object, Object> e : ((Map<Object, Object>) parsed).entrySet())
      map.put(String.valueOf(e.getKey()), e.getValue());
    return map;
  }

  /** Read the YAML template map back to the model and render its JSON for validation. */
  private static ObjectNode renderJson(Map<String, Object> yaml)
  {
    LinkedHashMap<String, Object> map = new LinkedHashMap<>(yaml);
    TemplateSchemaArtifact model = new YamlArtifactReader(true).readTemplateSchemaArtifact(map);
    return new JsonArtifactRenderer().renderTemplateSchemaArtifact(model);
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

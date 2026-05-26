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
 * Tests for the {@code template_from_yaml} tool.
 *
 * <p>The headline case mirrors {@code JsonArtifactRendererTest}'s validation invariant:
 * a YAML input compiles to a CEDAR JSON Schema that {@link CedarValidator#validateTemplate}
 * accepts.
 */
final class TemplateFromYamlToolTest
{
  private ModelValidator cedarValidator;
  private ObjectMapper jackson;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
    jackson = new ObjectMapper();
  }

  @Test void compiles_minimal_template_yaml_to_validated_json_schema() throws Exception
  {
    String yaml =
        "type: template\n"
            + "name: Patient demographics\n"
            + "description: Minimal demographics template\n"
            + "version: 0.1.0\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    ValidationReport report = cedarValidator.validateTemplate(rendered);
    if (!"true".equals(report.getValidationStatus())) {
      StringBuilder msg = new StringBuilder("CedarValidator rejected the compiled template:\n");
      for (ErrorItem err : report.getErrors()) msg.append("  - ").append(err).append('\n');
      fail(msg.toString());
    }

    assertEquals("Patient demographics", rendered.get("schema:name").asText());
    assertEquals("Minimal demographics template", rendered.get("schema:description").asText());
    assertEquals("0.1.0", rendered.get("pav:version").asText());
  }

  @Test void compiles_template_with_a_text_field_child() throws Exception
  {
    // Each child needs a `key` (its property identifier in the parent) and a `type`;
    // `name` and `description` are the human-readable labels carried into the rendered
    // template's _ui block.
    String yaml =
        "type: template\n"
            + "name: One-field template\n"
            + "description: A template with one text field\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: patient_name\n"
            + "    type: text-field\n"
            + "    name: Patient name\n"
            + "    description: Free-text patient name\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    // CEDAR templates expose child fields as properties.<key>.
    JsonNode patientName = rendered.path("properties").path("patient_name");
    assertTrue(patientName.isObject(),
        "child field 'patient_name' should appear under properties; got: " + rendered.path("properties"));

    // Defense in depth: validate again externally.
    ValidationReport report = cedarValidator.validateTemplate(rendered);
    assertEquals("true", report.getValidationStatus(),
        "compiled template must pass CedarValidator");
  }

  @Test void rejects_blank_yaml()
  {
    McpSchema.CallToolResult result = invoke(Map.of("yaml", "   \n  \n"));
    assertTrue(result.isError(), "blank yaml input must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("yaml"),
        "error should mention the offending argument; got: " + errorText(result));
  }

  @Test void rejects_yaml_with_wrong_top_level_type()
  {
    // type: element passed to a template-compiling tool — should fail at the reader,
    // not silently produce an element JSON.
    String yaml =
        "type: element\n"
            + "name: NotATemplate\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "type: element must not compile via template_from_yaml; got: " + result);
    assertTrue(errorText(result).toLowerCase().contains("template"),
        "error should explain that template was expected; got: " + errorText(result));
  }

  @Test void rejects_malformed_yaml_with_clean_error()
  {
    // Tab indentation inside a mapping triggers SnakeYAML's scanner error — the kind of
    // mistake an LLM will reliably make.
    String yaml = "type: template\n\tname: malformed\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(), "malformed yaml must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("yaml"),
        "error should identify yaml as the problem area; got: " + errorText(result));
  }

  @Test void rejects_yaml_that_is_a_bare_string()
  {
    McpSchema.CallToolResult result = invoke(Map.of("yaml", "just a string\n"));
    assertTrue(result.isError(), "non-mapping yaml must produce isError=true");
    assertTrue(errorText(result).toLowerCase().contains("mapping"),
        "error should mention the missing top-level mapping; got: " + errorText(result));
  }

  @Test void rejects_missing_yaml_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("yaml"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> arguments)
  {
    McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("template_from_yaml", arguments);
    return TemplateFromYamlTool.handler(null, request);
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

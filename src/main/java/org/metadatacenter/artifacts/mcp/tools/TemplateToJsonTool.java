package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code template_to_json} — the headline authoring path.
 *
 * <p>Takes a CEDAR YAML template description (the compact, LLM-friendly serialization)
 * and returns the canonical CEDAR JSON Schema (what downstream CEDAR tooling consumes).
 * The pipeline is the four-stage triangle the artifact library is built around:
 * <ol>
 *   <li>Parse the YAML text into a {@code LinkedHashMap} (SnakeYAML).</li>
 *   <li>Read the map into the in-memory {@link TemplateSchemaArtifact} model
 *       ({@link YamlArtifactReader}).</li>
 *   <li>Validate the rendered JSON Schema with {@link CedarValidator} (DESIGN.md
 *       Principle 6).</li>
 *   <li>Serialize and return the JSON Schema string.</li>
 * </ol>
 *
 * <p>Any failure surfaces as an {@code isError=true} content block (DESIGN.md
 * Principle 5), never as a JSON-RPC protocol error.
 */
public final class TemplateToJsonTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  // Compact-mode reader: accepts the compact YAML form template_to_yaml emits — same
  // YAML the LLM sees when editing a template. Missing modelVersion is defaulted, but
  // a wrong-valued modelVersion is still rejected.
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private TemplateToJsonTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "CEDAR template described in the artifact library's YAML format. The top-level "
            + "'type:' must be 'template'. The 'children:' list carries field and element "
            + "specifications. Full key vocabulary:\n\n"
            + YamlVocabulary.fullSchemaVocabulary()));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("template_to_json")
        .title("CEDAR template: YAML → JSON Schema")
        .description(
            "Compiles a CEDAR template described in YAML (the compact authoring format) "
                + "into the canonical CEDAR JSON Schema (what downstream CEDAR tooling "
                + "consumes). The returned JSON has been round-tripped through the "
                + "artifact library's reader/renderer and accepted by CedarValidator, so "
                + "a non-error result is a guaranteed-valid CEDAR template. Use this to export "
                + "the canonical JSON Schema for cedar-server and other downstream consumers.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawYaml = args.get("artifact");
    if (rawYaml == null)
      return error("artifact argument is required");
    String yamlText = rawYaml.toString();
    if (yamlText.isBlank())
      return error("artifact argument must not be blank");

    // Stage 1: parse YAML text -> LinkedHashMap via the shared no-timestamp parser, so
    // date-like temporal values stay strings rather than being coerced to java.util.Date.
    LinkedHashMap<String, Object> yamlMap;
    try {
      yamlMap = ArtifactExchange.parseYamlMap(yamlText);
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

    // Mint a top-level @id when the YAML omits one (DESIGN.md Principle 10). Only the
    // top-level map is touched; nested children under 'children:' are never given an id.
    Object suppliedId = yamlMap.get(YamlConstants.ID);
    if (suppliedId == null || suppliedId.toString().isBlank())
      yamlMap.put(YamlConstants.ID, IdMinter.mintTemplateId().toString());

    // Stage 2: read map -> in-memory model. ArtifactParseException is the library's
    // "this is structurally invalid CEDAR" signal — surface the field path / key it
    // identifies because that's what the LLM needs to fix its input.
    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(yamlMap);
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("template reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    // Stage 3: render JSON Schema. Validate with the canonical CedarValidator before
    // returning, mirroring the artifact library's own renderer-test invariant
    // (DESIGN.md Principle 6).
    ObjectNode rendered = RENDERER.renderTemplateSchemaArtifact(template);
    try {
      ValidationReport report = VALIDATOR.validateTemplate(rendered);
      if (!"true".equals(report.getValidationStatus()))
        return error("rendered template failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating rendered template: "
          + e.getMessage());
    }

    // Stage 4: serialize and return.
    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered template: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static String formatErrors(ValidationReport report)
  {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (ErrorItem err : report.getErrors()) {
      if (i++ > 0) sb.append("; ");
      sb.append(err.toString());
      if (i >= 5) {
        sb.append("; ... (").append(report.getErrors().size() - i).append(" more)");
        break;
      }
    }
    return sb.length() == 0 ? "(no error details)" : sb.toString();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

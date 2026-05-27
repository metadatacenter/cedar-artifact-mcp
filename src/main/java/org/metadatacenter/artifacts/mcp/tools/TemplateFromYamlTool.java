package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code template_from_yaml} — the headline authoring path.
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
public final class TemplateFromYamlTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  // Compact-mode reader: accepts the compact YAML form template_to_yaml emits — same
  // YAML the LLM sees when editing a template. Missing modelVersion is defaulted, but
  // a wrong-valued modelVersion is still rejected.
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private TemplateFromYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("yaml", Map.of(
        "type", "string",
        "description",
        "CEDAR template described in the artifact library's YAML format. Required "
            + "top-level keys: 'type: template', 'name', 'modelVersion: 1.6.0', "
            + "'version' (e.g. '0.0.1'), and 'status' (e.g. 'draft'). The 'children' "
            + "list carries field and element specifications. Field types are "
            + "kebab-case (e.g. 'text-field', 'controlled-term-field', 'numeric-field', "
            + "'temporal-field'). See the artifact library's YamlConstants for the "
            + "full vocabulary."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("yaml"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("template_from_yaml")
        .title("CEDAR template: YAML → JSON Schema")
        .description(
            "Compiles a CEDAR template described in YAML (the compact authoring format) "
                + "into the canonical CEDAR JSON Schema (what downstream CEDAR tooling "
                + "consumes). The returned JSON has been round-tripped through the "
                + "artifact library's reader/renderer and accepted by CedarValidator, so "
                + "a non-error result is a guaranteed-valid CEDAR template.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawYaml = args.get("yaml");
    if (rawYaml == null)
      return error("yaml argument is required");
    String yamlText = rawYaml.toString();
    if (yamlText.isBlank())
      return error("yaml argument must not be blank");

    // Stage 1: parse YAML text -> LinkedHashMap. SnakeYAML throws YAMLException
    // (a RuntimeException) for malformed input — catch broadly so we don't leak the
    // parse-error stack trace into the LLM-facing content.
    LinkedHashMap<String, Object> yamlMap;
    try {
      Object parsed = new Yaml().load(yamlText);
      if (!(parsed instanceof Map<?, ?>))
        return error("yaml must parse to a mapping at the top level (got "
            + (parsed == null ? "null" : parsed.getClass().getSimpleName()) + ")");
      yamlMap = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet())
        yamlMap.put(String.valueOf(entry.getKey()), entry.getValue());
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

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

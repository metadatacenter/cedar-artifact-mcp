package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code template_to_yaml} — renders a CEDAR template as YAML.
 *
 * <p>The input artifact may be either the expanded YAML exchange form (what the create/add/set
 * tools emit) or a JSON Schema — the format is auto-detected. This serves two jobs: re-rendering
 * a threaded YAML artifact in a different form (typically {@code isCompact: true} for a lean
 * display) without a JSON detour, and importing an externally-sourced JSON Schema into YAML.
 *
 * <p>The {@code isCompact} argument selects between the two forms the
 * {@link org.metadatacenter.artifacts.model.renderer.YamlArtifactRenderer} supports:
 * <ul>
 *   <li>{@code true} (default) — the lean authoring/display form. Provenance, status, version,
 *       and {@code modelVersion} are omitted.</li>
 *   <li>{@code false} — the expanded exchange form: every field the renderer can emit, so the
 *       artifact round-trips losslessly.</li>
 * </ul>
 *
 * <p>No CedarValidator step here: the output is YAML, not a CEDAR JSON Schema.
 */
public final class TemplateToYamlTool
{
  private TemplateToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "CEDAR template as YAML (the exchange form the create/add/set tools return) or as a "
            + "JSON Schema string. The format is auto-detected."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean, LLM-friendly compact form. true (default) omits "
            + "provenance, status, version, and modelVersion — the round-trip back to a model "
            + "defaults the absent modelVersion, so it reads cleanly. false emits the expanded "
            + "exchange form (every field the renderer can produce), which round-trips losslessly."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("template_to_yaml")
        .title("Render a CEDAR template as YAML")
        .description(
            "Renders a CEDAR template (YAML or JSON Schema) as YAML. 'isCompact' selects "
                + "compact (lean, default) or expanded full-fidelity output. Use it to recompact "
                + "an expanded YAML template for display, or to import a JSON Schema template into "
                + "YAML. Reverse direction of 'template_to_json'." + ArtifactExchange.VERBATIM_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawArtifact = args.get("artifact");
    if (rawArtifact == null)
      return error("artifact argument is required");
    String artifactText = rawArtifact.toString();
    if (artifactText.isBlank())
      return error("artifact argument must not be blank");

    boolean isCompact;
    Object rawIsCompact = args.get("isCompact");
    if (rawIsCompact == null) {
      isCompact = true;
    } else if (rawIsCompact instanceof Boolean b) {
      isCompact = b;
    } else {
      return error("isCompact must be a boolean (got "
          + rawIsCompact.getClass().getSimpleName() + ")");
    }

    TemplateSchemaArtifact template;
    try {
      template = ArtifactExchange.readTemplate(artifactText);
    } catch (RuntimeException e) {
      return error("artifact rejected by reader (must be a CEDAR template): " + e.getMessage());
    }

    String yaml;
    try {
      yaml = YamlSerializer.getYAML(template, isCompact, false);
    } catch (RuntimeException e) {
      return error("YAML rendering failed: " + e.getMessage());
    }
    if (yaml == null)
      return error("YAML rendering returned null");

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

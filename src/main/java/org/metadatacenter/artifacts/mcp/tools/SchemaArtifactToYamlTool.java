package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code schema_artifact_to_yaml} — renders a CEDAR schema artifact (template, element,
 * or field) as YAML. The input may be the expanded YAML exchange form (what the create/add/set
 * tools emit) or a JSON Schema — the format is auto-detected, as is the kind. This serves two
 * jobs: re-rendering a threaded YAML artifact in a different form (typically {@code isCompact:
 * true} for a lean display), and importing an externally-sourced JSON Schema into YAML.
 *
 * <p>{@code isCompact} selects the form: {@code true} (default) the lean authoring/display form
 * (provenance, status, version, {@code modelVersion} omitted); {@code false} the expanded
 * exchange form that round-trips losslessly. No CedarValidator step: the output is YAML, not a
 * CEDAR JSON Schema. Instances are not schema artifacts — they are redirected to
 * {@code instance_artifact_to_yaml}.
 */
public final class SchemaArtifactToYamlTool
{
  private SchemaArtifactToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR schema artifact — template, element, or field — as YAML (the exchange form the "
            + "create/add/set tools return) or as a JSON Schema string. Both the format and the "
            + "kind are auto-detected."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean, LLM-friendly compact form. true (default) omits provenance, "
            + "status, version, and modelVersion — the round-trip back to a model defaults the "
            + "absent modelVersion, so it reads cleanly. false emits the expanded exchange form "
            + "(every field the renderer can produce), which round-trips losslessly."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("schema_artifact_to_yaml")
        .title("Render a CEDAR schema artifact as YAML (auto-detect template/element/field)")
        .description(
            "Renders a CEDAR schema artifact (template, element, or field; YAML or JSON Schema) "
                + "as YAML. The kind is auto-detected. 'isCompact' selects compact (lean, default) "
                + "or expanded full-fidelity output. Use it to recompact an expanded YAML artifact "
                + "for display, or to import a JSON Schema into YAML. Reverse direction of "
                + "schema_artifact_to_json. (For a template or element instance use "
                + "instance_artifact_to_yaml.)" + ArtifactExchange.VERBATIM_NOTICE)
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

    ObjectNode node;
    try {
      node = ArtifactExchange.toObjectNode(artifactText);
    } catch (RuntimeException e) {
      return error("artifact could not be parsed as a CEDAR schema artifact (YAML or JSON): "
          + e.getMessage());
    }

    ArtifactKinds.Kind kind = ArtifactKinds.detect(node);
    if (kind == ArtifactKinds.Kind.INSTANCE || kind == null)
      return error("this does not look like a schema artifact (template, element, or field) — "
          + "an instance is rendered with instance_artifact_to_yaml");

    String yaml;
    try {
      yaml = ArtifactExchange.jsonNodeToYaml(node, isCompact);
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

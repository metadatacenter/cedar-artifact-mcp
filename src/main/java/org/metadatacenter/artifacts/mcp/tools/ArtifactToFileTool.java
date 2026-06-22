package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code artifact_to_file} — writes a CEDAR artifact (supplied inline as YAML or JSON) to
 * an absolute file path, as YAML or JSON.
 *
 * <p>Returns only a short summary (path, kind, size) — never the content — so saving a large
 * artifact costs no tokens. The output format follows the path extension ({@code .json} → JSON,
 * otherwise YAML) unless overridden.
 */
public final class ArtifactToFileTool
{
  private ArtifactToFileTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "The CEDAR artifact to write, inline as YAML (the compact exchange form) or JSON."));
    properties.put("path", Map.of(
        "type", "string",
        "description",
        "Absolute path to write to. The output format is taken from the extension (.json → JSON, "
            + ".yaml/.yml → YAML) unless 'format' overrides it. e.g. /Users/me/templates/study.yaml"));
    properties.put("format", Map.of(
        "type", "string",
        "enum", List.of("yaml", "json"),
        "description",
        "Override the output format; by default it is inferred from the path extension, else YAML."));
    properties.put("compact", Map.of(
        "type", "boolean",
        "description",
        "When writing YAML, emit the lean compact form (drops provenance, version, status). "
            + "Default false (expanded, lossless)."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact", "path"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("artifact_to_file")
        .title("Write a CEDAR artifact to a file")
        .description(
            "Writes a CEDAR artifact (supplied inline as YAML or JSON) to an absolute file path. "
                + "The output format is inferred from the path extension (.json → JSON, otherwise "
                + "YAML) unless 'format' overrides it. Returns only a short summary (path, kind, "
                + "size) — never the content — so saving a large artifact costs no tokens. Handy "
                + "for exporting the JSON a non-YAML downstream CEDAR tool needs. Path must be "
                + "absolute. Overwrites an existing file and creates parent directories. Does not "
                + "validate.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String artifact = stringArg(args, "artifact");
    if (artifact == null || artifact.isBlank())
      return error("artifact is required and must not be blank");

    Path path;
    boolean asYaml;
    boolean compact;
    try {
      path = ArtifactFiles.requireAbsolute(stringArg(args, "path"), "path");
      asYaml = ArtifactFiles.wantsYaml(stringArg(args, "format"), path);
      compact = ArtifactFiles.compactFlag(args.get("compact"));
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    String rendered;
    try {
      rendered = ArtifactExchange.renderArtifact(artifact, asYaml, compact);
    } catch (RuntimeException e) {
      return error("artifact could not be parsed (YAML or JSON): " + e.getMessage());
    }

    long bytes;
    try {
      bytes = ArtifactFiles.write(path, rendered);
    } catch (IOException e) {
      return error("could not write " + path + ": " + e.getMessage());
    }

    return success("Wrote " + ArtifactExchange.kindLabel(artifact) + " to " + path + " ("
        + bytes + " bytes, " + (asYaml ? "YAML" : "JSON") + ").");
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static McpSchema.CallToolResult success(String text)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, text))).isError(false).build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message))).isError(true).build();
  }
}

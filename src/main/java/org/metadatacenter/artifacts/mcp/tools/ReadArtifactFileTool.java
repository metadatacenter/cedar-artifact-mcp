package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code read_artifact_file} — reads a CEDAR artifact from an absolute file path (JSON or
 * YAML, auto-detected) and returns it as YAML (the compact exchange form, by default) or as JSON.
 *
 * <p>The point is to pull a large artifact file into the conversation without pasting it: a big
 * JSON file comes back as YAML roughly a tenth the size. To convert a file without bringing the
 * content into the conversation at all, use {@code convert_artifact_file}.
 */
public final class ReadArtifactFileTool
{
  private ReadArtifactFileTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("path", Map.of(
        "type", "string",
        "description",
        "Absolute path to a CEDAR artifact file (JSON or YAML; the format is auto-detected). "
            + "e.g. /Users/me/templates/study.json"));
    properties.put("format", Map.of(
        "type", "string",
        "enum", List.of("yaml", "json"),
        "description",
        "Output format: 'yaml' (the default) — the compact exchange form, an order of magnitude "
            + "smaller than JSON — or 'json'."));
    properties.put("compact", Map.of(
        "type", "boolean",
        "description",
        "When rendering YAML, emit the lean compact form (drops provenance, version, status). "
            + "Default false (expanded, lossless). YAML output only — an error with format: json."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("path"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("read_artifact_file")
        .title("Load a CEDAR artifact from a file")
        .description(
            "Reads a CEDAR artifact (template, element, field, or instance) from an absolute file "
                + "path — JSON or YAML, auto-detected — and returns it as YAML (the compact "
                + "exchange form, by default) or as JSON. Use this to pull a large artifact file "
                + "into the conversation without pasting it: a big JSON file comes back as YAML "
                + "roughly a tenth the size. Path must be absolute. Does not validate (run "
                + "validate_schema_artifact / validate_instance_artifact, or rely on the server "
                + "on upload). To convert a file without bringing its content into the conversation "
                + "at all, use convert_artifact_file." + ArtifactExchange.VERBATIM_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Path path;
    boolean asYaml;
    boolean compact;
    try {
      path = ArtifactFiles.requireAbsolute(stringArg(args, "path"), "path");
      asYaml = outputIsYaml(stringArg(args, "format"));
      compact = ArtifactFiles.compactFlag(args.get("compact"));
      ArtifactFiles.requireCompactCompatibleWith(asYaml, compact);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    String text;
    try {
      text = ArtifactFiles.read(path);
    } catch (IOException e) {
      return error("could not read " + path + ": " + e.getMessage());
    }

    String rendered;
    try {
      rendered = ArtifactExchange.renderArtifact(text, asYaml, compact);
    } catch (RuntimeException e) {
      return error("file is not a parseable CEDAR artifact (YAML or JSON): " + e.getMessage());
    }
    return success(rendered);
  }

  /** Output format for from_file: explicit yaml/json, defaulting to YAML (the source extension is irrelevant). */
  private static boolean outputIsYaml(String formatArg)
  {
    if (formatArg == null || formatArg.isBlank())
      return true;
    String format = formatArg.trim().toLowerCase();
    if (format.equals("yaml") || format.equals("yml"))
      return true;
    if (format.equals("json"))
      return false;
    throw new IllegalArgumentException("format must be 'yaml' or 'json' (got '" + formatArg + "')");
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

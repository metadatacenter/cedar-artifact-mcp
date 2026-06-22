package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code convert_artifact_file} — reads a CEDAR artifact from one absolute file path and
 * writes it to another in the requested format, entirely on disk.
 *
 * <p>The artifact never enters the conversation, so this is the way to convert a large JSON
 * artifact to YAML (≈10× smaller) or vice versa without spending any tokens on the content.
 */
public final class ConvertArtifactFileTool
{
  private ConvertArtifactFileTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("source_path", Map.of(
        "type", "string",
        "description",
        "Absolute path to the artifact file to read (JSON or YAML; the format is auto-detected)."));
    properties.put("dest_path", Map.of(
        "type", "string",
        "description",
        "Absolute path to write the converted artifact to. The output format is taken from its "
            + "extension (.json → JSON, .yaml/.yml → YAML) unless 'format' overrides it."));
    properties.put("format", Map.of(
        "type", "string",
        "enum", List.of("yaml", "json"),
        "description",
        "Override the output format; by default it is inferred from dest_path's extension, else YAML."));
    properties.put("compact", Map.of(
        "type", "boolean",
        "description",
        "When writing YAML, emit the lean compact form (drops provenance, version, status). "
            + "Default false (expanded, lossless). YAML output only — an error with JSON output."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("source_path", "dest_path"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("convert_artifact_file")
        .title("Convert a CEDAR artifact file (file to file)")
        .description(
            "Reads a CEDAR artifact from source_path and writes it to dest_path in the requested "
                + "format — entirely on disk, so the artifact never enters the conversation. This "
                + "is the way to convert a large JSON artifact to YAML (about a tenth the size) or "
                + "vice versa without spending any tokens on the content. The output format is "
                + "inferred from dest_path's extension (.json → JSON, otherwise YAML) unless "
                + "'format' overrides it. Both paths must be absolute. Returns only a summary. "
                + "Overwrites dest_path and creates parent directories. Does not validate.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Path source;
    Path dest;
    boolean asYaml;
    boolean compact;
    try {
      source = ArtifactFiles.requireAbsolute(stringArg(args, "source_path"), "source_path");
      dest = ArtifactFiles.requireAbsolute(stringArg(args, "dest_path"), "dest_path");
      asYaml = ArtifactFiles.wantsYaml(stringArg(args, "format"), dest);
      compact = ArtifactFiles.compactFlag(args.get("compact"));
      ArtifactFiles.requireCompactCompatibleWith(asYaml, compact);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    String text;
    try {
      text = ArtifactFiles.read(source);
    } catch (IOException e) {
      return error("could not read " + source + ": " + e.getMessage());
    }

    String rendered;
    try {
      rendered = ArtifactExchange.renderArtifact(text, asYaml, compact);
    } catch (RuntimeException e) {
      return error("source is not a parseable CEDAR artifact (YAML or JSON): " + e.getMessage());
    }

    long bytes;
    try {
      bytes = ArtifactFiles.write(dest, rendered);
    } catch (IOException e) {
      return error("could not write " + dest + ": " + e.getMessage());
    }

    return success("Converted " + ArtifactExchange.kindLabel(text) + " to "
        + (asYaml ? "YAML" : "JSON") + ": " + source + " → " + dest + " (" + bytes + " bytes).");
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

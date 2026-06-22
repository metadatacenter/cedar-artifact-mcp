package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the artifact file tools: {@code artifact_from_file}, {@code artifact_to_file}, and
 * {@code convert_artifact_file}. Covers format selection (explicit + extension-inferred), the
 * absolute-path requirement, and that the write tools return a summary rather than echoing content.
 */
final class ArtifactFileToolsTest
{
  @Test void writes_json_by_extension_then_loads_back_as_yaml(@TempDir Path dir) throws IOException
  {
    Path jsonFile = dir.resolve("study.json");

    McpSchema.CallToolResult write = toFile(Map.of(
        "artifact", createTemplate("Study"), "path", jsonFile.toString()));
    assertFalse(write.isError(), errorText(write));
    assertTrue(textOf(write).contains("Wrote") && textOf(write).contains("bytes"), textOf(write));
    assertFalse(textOf(write).contains("schema:name"),
        "write must return a summary, not echo the content; got: " + textOf(write));

    assertTrue(Files.readString(jsonFile).contains("\"@type\""),
        "the .json extension should produce JSON on disk");

    McpSchema.CallToolResult load = fromFile(Map.of("path", jsonFile.toString()));
    assertFalse(load.isError(), errorText(load));
    assertTrue(textOf(load).contains("type: template"),
        "from_file defaults to YAML; got:\n" + textOf(load));
  }

  @Test void from_file_can_return_json(@TempDir Path dir) throws IOException
  {
    Path yamlFile = dir.resolve("study.yaml");
    Files.writeString(yamlFile, createTemplate("Study"));

    McpSchema.CallToolResult load = fromFile(Map.of("path", yamlFile.toString(), "format", "json"));
    assertFalse(load.isError(), errorText(load));
    assertTrue(textOf(load).contains("\"@type\""), "format json must return JSON; got:\n" + textOf(load));
  }

  @Test void converts_file_to_file_without_echoing_content(@TempDir Path dir) throws IOException
  {
    Path jsonFile = dir.resolve("study.json");
    Files.writeString(jsonFile, textOf(toJson(createTemplate("Study"))));  // seed a JSON file

    Path yamlFile = dir.resolve("out/study.yaml");  // nested dir is created
    McpSchema.CallToolResult convert = convert(Map.of(
        "source_path", jsonFile.toString(), "dest_path", yamlFile.toString()));

    assertFalse(convert.isError(), errorText(convert));
    assertTrue(textOf(convert).contains("Converted"), textOf(convert));
    assertFalse(textOf(convert).contains("schema:name"),
        "convert must return a summary, not the content; got: " + textOf(convert));
    assertTrue(Files.readString(yamlFile).contains("type: template"),
        "dest should be YAML; got:\n" + Files.readString(yamlFile));
  }

  @Test void rejects_a_relative_path()
  {
    McpSchema.CallToolResult result = fromFile(Map.of("path", "relative/study.json"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("absolute"), errorText(result));
  }

  @Test void rejects_compact_with_json_output(@TempDir Path dir)
  {
    // compact is a YAML-only notion; pairing it with JSON output is a mistake, not a silent no-op.
    McpSchema.CallToolResult result = toFile(Map.of(
        "artifact", createTemplate("Study"),
        "path", dir.resolve("study.json").toString(),  // .json → JSON output
        "compact", true));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("compact applies only to YAML"), errorText(result));
  }

  // helpers

  private static McpSchema.CallToolResult fromFile(Map<String, Object> args)
  {
    return ArtifactFromFileTool.handler(null, new McpSchema.CallToolRequest("artifact_from_file", args));
  }

  private static McpSchema.CallToolResult toFile(Map<String, Object> args)
  {
    return ArtifactToFileTool.handler(null, new McpSchema.CallToolRequest("artifact_to_file", args));
  }

  private static McpSchema.CallToolResult convert(Map<String, Object> args)
  {
    return ConvertArtifactFileTool.handler(null, new McpSchema.CallToolRequest("convert_artifact_file", args));
  }

  private static McpSchema.CallToolResult toJson(String artifact)
  {
    return invokeTool(SchemaArtifactToJsonTool::handler, "schema_artifact_to_json",
        Map.of("artifact", artifact));
  }

  private static String createTemplate(String name)
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", name)));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(McpSyncServerExchange e, McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture '" + name + "' must succeed; got: " + errorText(result));
    return result;
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

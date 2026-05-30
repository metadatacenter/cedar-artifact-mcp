package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code element_to_yaml} — element variant of {@code template_to_yaml}.
 *
 * <p>Takes a CEDAR element JSON Schema and returns the artifact library's YAML
 * serialization. The {@code isCompact} flag matches the same contract as
 * {@code template_to_yaml}: compact YAML round-trips through
 * {@code element_to_json} via the reader's compact mode.
 */
public final class ElementToYamlTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();

  private ElementToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("json", Map.of(
        "type", "string",
        "description",
        "CEDAR element as a JSON Schema string. Must parse to a JSON object that "
            + "the artifact library's JsonArtifactReader accepts as an element (i.e. "
            + "the kind of JSON 'element_to_json' returns)."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean, LLM-friendly compact form. true (default) omits "
            + "provenance, status, version, and modelVersion — 'element_to_json' "
            + "reads compact YAML cleanly (it defaults the absent modelVersion), so "
            + "the round-trip works without manual repair. false emits every field "
            + "the renderer can produce."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("json"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("element_to_yaml")
        .title("CEDAR element: JSON Schema → YAML")
        .description(
            "Renders a CEDAR element JSON Schema as YAML. Reverse direction of "
                + "'element_to_json'. See 'template_to_yaml' for the form contract.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawJson = args.get("json");
    if (rawJson == null)
      return error("json argument is required");
    String jsonText = rawJson.toString();
    if (jsonText.isBlank())
      return error("json argument must not be blank");

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

    JsonNode parsed;
    try {
      parsed = JACKSON2.readTree(jsonText);
    } catch (Exception e) {
      return error("JSON parse failed: " + e.getMessage());
    }
    if (!(parsed instanceof ObjectNode jsonObject))
      return error("json must parse to a JSON object (got "
          + (parsed == null ? "null" : parsed.getNodeType().toString().toLowerCase()) + ")");

    ElementSchemaArtifact element;
    try {
      element = READER.readElementSchemaArtifact(jsonObject);
    } catch (ArtifactParseException e) {
      return error("CEDAR JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("element reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    String yaml;
    try {
      yaml = YamlSerializer.getYAML(element, isCompact, false);
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

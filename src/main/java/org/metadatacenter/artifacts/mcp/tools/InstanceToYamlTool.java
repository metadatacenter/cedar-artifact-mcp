package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code instance_to_yaml} — reverse direction of {@code instance_from_yaml}.
 * Takes a CEDAR JSON instance and returns YAML, with the same {@code isCompact} flag
 * as the schema-side {@code *_to_yaml} tools.
 */
public final class InstanceToYamlTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();

  private InstanceToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("json", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as a JSON string (the kind 'instance_from_yaml' "
            + "returns, or what a CEDAR repository serves for a saved instance)."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean, LLM-friendly compact form. true (default) omits "
            + "provenance fields; 'instance_from_yaml' reads compact YAML cleanly. "
            + "false emits every field the renderer can produce."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("json"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("instance_to_yaml")
        .title("CEDAR template instance: JSON → YAML")
        .description(
            "Renders a CEDAR template instance JSON as YAML. The 'isCompact' argument "
                + "selects compact (LLM-friendly, default) or full-fidelity output. "
                + "Reverse direction of 'instance_from_yaml'.")
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

    TemplateInstanceArtifact instance;
    try {
      instance = READER.readTemplateInstanceArtifact(jsonObject);
    } catch (ArtifactParseException e) {
      return error("CEDAR JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("instance reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    String yaml;
    try {
      yaml = YamlSerializer.getYAML(instance, isCompact, false);
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

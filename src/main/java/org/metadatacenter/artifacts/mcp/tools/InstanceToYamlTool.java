package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code instance_to_yaml} — renders a CEDAR template instance as YAML.
 *
 * <p>Accepts the instance as YAML (the exchange form 'create_instance' returns) or JSON
 * (auto-detected). {@code isCompact} matches the schema-side {@code *_to_yaml} tools:
 * re-render an expanded YAML instance compact for display, or import a JSON instance into YAML.
 */
public final class InstanceToYamlTool
{
  private InstanceToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the exchange form 'create_instance' returns) or as "
            + "a JSON string (what a CEDAR repository serves for a saved instance). The format "
            + "is auto-detected."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean compact form (default true) or the expanded, "
            + "losslessly-round-tripping exchange form (false). See 'template_to_yaml'."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("instance_to_yaml")
        .title("Render a CEDAR template instance as YAML")
        .description(
            "Renders a CEDAR template instance (YAML or JSON) as YAML. The 'isCompact' argument "
                + "selects compact (lean, default) or expanded full-fidelity output. Reverse "
                + "direction of 'instance_to_json'." + ArtifactExchange.VERBATIM_NOTICE)
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

    TemplateInstanceArtifact instance;
    try {
      instance = ArtifactExchange.readInstance(artifactText);
    } catch (RuntimeException e) {
      return error("artifact rejected by reader (must be a CEDAR template instance): " + e.getMessage());
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

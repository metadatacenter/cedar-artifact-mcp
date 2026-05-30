package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code element_to_yaml} — element variant of {@code template_to_yaml}.
 *
 * <p>Accepts a CEDAR element as YAML (the exchange form) or JSON Schema (auto-detected) and
 * renders YAML. {@code isCompact} matches the {@code template_to_yaml} contract: re-render an
 * expanded YAML element compact for display, or import a JSON Schema element into YAML.
 */
public final class ElementToYamlTool
{
  private ElementToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "CEDAR element as YAML (the exchange form 'create_element' returns) or as a JSON "
            + "Schema string. The format is auto-detected."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean compact form (default true) or the expanded, "
            + "losslessly-round-tripping exchange form (false). See 'template_to_yaml'."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("element_to_yaml")
        .title("Render a CEDAR element as YAML")
        .description(
            "Renders a CEDAR element (YAML or JSON Schema) as YAML. Reverse direction of "
                + "'element_to_json'. See 'template_to_yaml' for the form contract.")
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

    ElementSchemaArtifact element;
    try {
      element = ArtifactExchange.readElement(artifactText);
    } catch (RuntimeException e) {
      return error("artifact rejected by reader (must be a CEDAR element): " + e.getMessage());
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

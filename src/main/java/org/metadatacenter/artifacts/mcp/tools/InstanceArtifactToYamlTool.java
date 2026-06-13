package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code instance_artifact_to_yaml} — renders a CEDAR instance as YAML. The input may be
 * the YAML exchange form (what {@code create_template_instance} / {@code create_element_instance}
 * return) or a JSON instance (what a CEDAR repository serves) — the format is auto-detected, as is
 * the kind (template instance vs element instance).
 *
 * <p>{@code isCompact} selects the form: {@code true} (default) the lean display form;
 * {@code false} the expanded, losslessly-round-tripping exchange form. No CedarValidator step:
 * the output is YAML. Instances are sparse in either form — a field with no value is omitted (no
 * inflation happens on the YAML side; use {@code instance_artifact_to_json} with the schema to get
 * a complete instance).
 */
public final class InstanceArtifactToYamlTool
{
  private InstanceArtifactToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance_artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR instance as YAML (the exchange form create_template_instance / "
            + "create_element_instance return) or as a JSON string (what a CEDAR repository serves "
            + "for a saved instance). Both the format and the kind (template instance vs element "
            + "instance) are auto-detected."));
    properties.put("isCompact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean compact form (default true) or the expanded, "
            + "losslessly-round-tripping exchange form (false). See schema_artifact_to_yaml."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("instance_artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("instance_artifact_to_yaml")
        .title("Render a CEDAR instance as YAML (auto-detect template/element instance)")
        .description(
            "Renders a CEDAR instance (YAML or JSON) as YAML. Auto-detects a template instance vs "
                + "an element instance. 'isCompact' selects compact (lean, default) or expanded "
                + "full-fidelity output. Reverse direction of instance_artifact_to_json."
                + ArtifactExchange.VERBATIM_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawInstance = args.get("instance_artifact");
    if (rawInstance == null)
      return error("instance_artifact argument is required");
    String instanceText = rawInstance.toString();
    if (instanceText.isBlank())
      return error("instance_artifact argument must not be blank");

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

    boolean isElement;
    try {
      isElement = ArtifactExchange.isElementInstance(instanceText);
    } catch (RuntimeException e) {
      return error("artifact could not be parsed as a CEDAR instance (YAML or JSON): "
          + e.getMessage());
    }

    String yaml;
    try {
      yaml = isElement
          ? ArtifactExchange.toYaml(ArtifactExchange.readElementInstance(instanceText), isCompact)
          : ArtifactExchange.toYaml(ArtifactExchange.readInstance(instanceText), isCompact);
    } catch (RuntimeException e) {
      return error("artifact rejected by reader (must be a CEDAR template or element instance): "
          + e.getMessage());
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

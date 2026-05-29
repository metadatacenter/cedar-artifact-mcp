package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code instance_from_yaml} — compiles a YAML-described template instance
 * to its canonical CEDAR JSON instance artifact.
 *
 * <p>Same compact-mode reader as the schema {@code *_from_yaml} tools; runs a YAML →
 * model → JSON pipeline. Instance validation against a specific template is done by
 * {@link ValidateInstanceTool} (which needs both the instance and its template), not
 * here.
 */
public final class InstanceFromYamlTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private InstanceFromYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("yaml", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance described in the artifact library's YAML format. Full "
            + "key vocabulary and value-shape conventions:\n\n"
            + YamlVocabulary.instanceVocabulary()));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("yaml"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("instance_from_yaml")
        .title("CEDAR template instance: YAML → JSON")
        .description(
            "Compiles a CEDAR template instance described in YAML into the canonical "
                + "CEDAR JSON instance. Use 'validate_instance' to verify the result "
                + "against a specific template."
                + YamlVocabulary.YAML_PREFERRED_DISPLAY_NUDGE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawYaml = args.get("yaml");
    if (rawYaml == null)
      return error("yaml argument is required");
    String yamlText = rawYaml.toString();
    if (yamlText.isBlank())
      return error("yaml argument must not be blank");

    LinkedHashMap<String, Object> yamlMap;
    try {
      Object parsed = new Yaml().load(yamlText);
      if (!(parsed instanceof Map<?, ?>))
        return error("yaml must parse to a mapping at the top level (got "
            + (parsed == null ? "null" : parsed.getClass().getSimpleName()) + ")");
      yamlMap = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet())
        yamlMap.put(String.valueOf(entry.getKey()), entry.getValue());
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

    // Mint the instance's own @id when the YAML omits one (DESIGN.md Principle 10). This is
    // the instance's identity (the top-level 'id' key), distinct from 'isBasedOn'.
    Object suppliedId = yamlMap.get(YamlConstants.ID);
    if (suppliedId == null || suppliedId.toString().isBlank())
      yamlMap.put(YamlConstants.ID, IdMinter.mintInstanceId().toString());

    TemplateInstanceArtifact instance;
    try {
      instance = READER.readTemplateInstanceArtifact(yamlMap);
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("instance reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(instance);

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered instance: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
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

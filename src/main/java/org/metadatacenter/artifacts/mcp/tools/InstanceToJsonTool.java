package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code instance_to_json} — compiles a YAML-described template instance
 * to its canonical CEDAR JSON instance artifact.
 *
 * <p>Same compact-mode reader as the schema {@code *_to_json} tools; runs a YAML →
 * model → JSON pipeline. Instance validation against a specific template is done by
 * {@link ValidateInstanceTool} (which needs both the instance and its template), not
 * here.
 */
public final class InstanceToJsonTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private InstanceToJsonTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance described in the artifact library's YAML format. Full "
            + "key vocabulary and value-shape conventions:\n\n"
            + YamlVocabulary.instanceVocabulary()));
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "The CEDAR template the instance is based on (YAML or JSON Schema). Optional but "
            + "recommended: a YAML instance is sparse — fields with no value are omitted — "
            + "whereas a canonical CEDAR JSON instance must carry every template field. When "
            + "supplied, the instance is inflated against the template so the exported JSON is "
            + "complete (and will validate); when omitted, only the fields the instance actually "
            + "carries are exported."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("instance_to_json")
        .title("CEDAR template instance: YAML → JSON")
        .description(
            "Exports a CEDAR template instance (YAML, the exchange form) to the canonical "
                + "CEDAR JSON instance for cedar-server and other downstream consumers. Use "
                + "'validate_instance' to verify the result against a specific template.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawYaml = args.get("artifact");
    if (rawYaml == null)
      return error("artifact argument is required");
    String yamlText = rawYaml.toString();
    if (yamlText.isBlank())
      return error("artifact argument must not be blank");

    LinkedHashMap<String, Object> yamlMap;
    try {
      yamlMap = ArtifactExchange.parseYamlMap(yamlText);
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

    // A YAML instance is sparse (unset fields omitted). When the template is supplied, inflate
    // against it so the exported JSON carries every required field; otherwise export as-is.
    Object rawTemplate = args.get("template");
    if (rawTemplate != null && !rawTemplate.toString().isBlank()) {
      try {
        TemplateSchemaArtifact template = ArtifactExchange.readTemplate(rawTemplate.toString());
        instance = InstanceInflater.inflate(template, instance);
      } catch (RuntimeException e) {
        return error("template supplied but the instance could not be inflated against it: "
            + e.getMessage());
      }
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

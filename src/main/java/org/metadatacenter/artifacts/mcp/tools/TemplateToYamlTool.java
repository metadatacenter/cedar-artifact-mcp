package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code template_to_yaml} — reverse direction of {@code template_from_yaml}.
 *
 * <p>Takes a CEDAR template JSON Schema and returns the artifact library's YAML
 * serialization. The {@code form} argument selects between the two forms the
 * {@link org.metadatacenter.artifacts.model.renderer.YamlArtifactRenderer} supports:
 * <ul>
 *   <li>{@code compact} — the lean, LLM-friendly authoring form (default). Provenance,
 *       status, version, and {@code modelVersion} are omitted. {@code template_from_yaml}
 *       reads in compact mode and defaults the absent {@code modelVersion}, so the
 *       output round-trips cleanly.</li>
 *   <li>{@code standard} — every field the renderer can emit, suitable for archival or
 *       diff workflows where provenance and version metadata need to survive.</li>
 * </ul>
 *
 * <p>No CedarValidator step here: the output is YAML, not a CEDAR JSON Schema. The input
 * JSON is parsed through {@link JsonArtifactReader#readTemplateSchemaArtifact}, which is
 * the same path the library's own round-trip tests use to drive the renderer.
 */
public final class TemplateToYamlTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();

  private static final String FORM_COMPACT = "compact";
  private static final String FORM_STANDARD = "standard";

  private TemplateToYamlTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("json", Map.of(
        "type", "string",
        "description",
        "CEDAR template as a JSON Schema string. Must parse to a JSON object that "
            + "the artifact library's JsonArtifactReader accepts as a template (i.e. "
            + "the kind of JSON 'template_from_yaml' returns)."));
    properties.put("form", Map.of(
        "type", "string",
        "enum", List.of(FORM_COMPACT, FORM_STANDARD),
        "description",
        "Which YAML form to render. 'compact' (default) emits the lean, LLM-friendly "
            + "authoring form — provenance, status, version, and modelVersion are all "
            + "omitted. 'template_from_yaml' reads compact YAML cleanly (it defaults "
            + "the absent modelVersion), so the round-trip works without manual repair. "
            + "'standard' emits every field the renderer can produce, suitable for "
            + "archival workflows where provenance metadata needs to survive."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("json"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("template_to_yaml")
        .title("CEDAR template: JSON Schema → YAML")
        .description(
            "Renders a CEDAR template JSON Schema as YAML. The 'form' argument selects "
                + "compact (LLM-friendly, default) or standard (full-fidelity) output. "
                + "Reverse direction of 'template_from_yaml'.")
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

    Object rawForm = args.get("form");
    boolean isCompact;
    if (rawForm == null) {
      isCompact = true;
    } else {
      String form = rawForm.toString();
      if (FORM_COMPACT.equals(form)) {
        isCompact = true;
      } else if (FORM_STANDARD.equals(form)) {
        isCompact = false;
      } else {
        return error("form must be '" + FORM_COMPACT + "' or '" + FORM_STANDARD
            + "' (got '" + form + "')");
      }
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

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(jsonObject);
    } catch (ArtifactParseException e) {
      return error("CEDAR JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("template reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    String yaml;
    try {
      yaml = YamlSerializer.getYAML(template, isCompact, false);
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code instance_artifact_to_json} — export escape hatch that renders a CEDAR instance
 * (YAML exchange form) to a CEDAR JSON instance, for the narrow case where a downstream CEDAR tool
 * or service cannot consume YAML. Auto-detects whether the input is a template instance or an
 * element instance from its {@code type:} discriminator and renders accordingly; a missing
 * top-level {@code @id} is minted with the matching IRI prefix (DESIGN.md Principle 10).
 *
 * <p>A YAML instance is sparse (unset fields omitted), whereas a CEDAR JSON instance
 * carries every field its schema declares. When the optional {@code schema_artifact} (the
 * template or element the instance is based on) is supplied, the instance is inflated against it
 * so the exported JSON is complete; otherwise only the fields the instance actually carries are
 * exported.
 */
public final class InstanceArtifactToJsonTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private InstanceArtifactToJsonTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance_artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR instance described in the artifact library's YAML format — a template instance "
            + "(type: instance) or an element instance (type: element-instance); the kind is "
            + "auto-detected. Full key vocabulary and value-shape conventions:\n\n"
            + YamlVocabulary.instanceVocabulary()));
    properties.put("schema_artifact", Map.of(
        "type", "string",
        "description",
        "The schema the instance is based on (YAML or JSON Schema) — a template for a template "
            + "instance, an element for an element instance. Optional but recommended: a YAML "
            + "instance is sparse (fields with no value are omitted), whereas a CEDAR "
            + "JSON instance must carry every field the schema declares. When supplied, the "
            + "instance is inflated against it so the exported JSON is complete (and will "
            + "validate); when omitted, only the fields the instance actually carries are "
            + "exported."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("instance_artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("instance_artifact_to_json")
        .title("CEDAR instance: YAML → JSON (auto-detect template/element instance)")
        .description(
            "Export escape hatch: use only when a downstream CEDAR tool or service cannot consume "
                + "YAML. JSON is far larger than YAML; for reading, writing, displaying, and "
                + "exchanging instances prefer instance_artifact_to_yaml. Renders a CEDAR template "
                + "instance or element instance (auto-detected; YAML exchange form) to a CEDAR JSON "
                + "instance. Supply the optional schema_artifact (the template or element it is "
                + "based on) to inflate the sparse instance to a complete JSON instance; omit it to "
                + "export only the fields present. Use validate_instance_artifact to verify the "
                + "result. (For a standalone template, element, or field — a schema, not an "
                + "instance — use schema_artifact_to_json.)")
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

    LinkedHashMap<String, Object> yamlMap;
    try {
      yamlMap = ArtifactExchange.parseYamlMap(instanceText);
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

    boolean isElement = "element-instance".equals(String.valueOf(yamlMap.get("type")));

    // Mint the instance's own @id when the YAML omits one (DESIGN.md Principle 10). This is the
    // instance's identity (the top-level 'id' key), distinct from 'isBasedOn'.
    Object suppliedId = yamlMap.get(YamlConstants.ID);
    if (suppliedId == null || suppliedId.toString().isBlank())
      yamlMap.put(YamlConstants.ID,
          (isElement ? IdMinter.mintElementInstanceId() : IdMinter.mintInstanceId()).toString());

    Object rawSchema = args.get("schema_artifact");
    String schemaText = rawSchema == null || rawSchema.toString().isBlank() ? null : rawSchema.toString();

    ObjectNode rendered;
    try {
      if (isElement) {
        ElementInstanceArtifact instance = READER.readElementInstanceArtifact(yamlMap);
        if (schemaText != null) {
          ElementSchemaArtifact element = ArtifactExchange.readElement(schemaText);
          instance = InstanceInflater.inflateElement(element, instance);
        }
        rendered = RENDERER.renderElementInstanceArtifact(instance);
      } else {
        TemplateInstanceArtifact instance = READER.readTemplateInstanceArtifact(yamlMap);
        if (schemaText != null) {
          TemplateSchemaArtifact template = ArtifactExchange.readTemplate(schemaText);
          instance = InstanceInflater.inflate(template, instance);
        }
        rendered = RENDERER.renderTemplateInstanceArtifact(instance);
      }
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      if (schemaText != null)
        return error("schema_artifact supplied but the instance could not be inflated against it "
            + "(is it the right template/element?): " + e.getMessage());
      return error("could not read the input as a CEDAR instance — if this is a standalone "
          + "template, element, or field (a schema artifact, not an instance), use "
          + "schema_artifact_to_json instead. Reader said: " + e.getMessage());
    }

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

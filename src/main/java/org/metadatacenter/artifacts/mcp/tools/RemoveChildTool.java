package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code remove_child} — removes a field or element child from a CEDAR
 * template or element parent JSON.
 *
 * <p>Auto-detects parent kind from {@code @type} and child kind by looking up the
 * key in the parent's {@code fieldSchemas} vs {@code elementSchemas}. The result is
 * re-validated with the matching {@code CedarValidator} method. The library's
 * {@code Builder.withoutFieldSchema / withoutElementSchema} primitives handle the
 * actual removal — including the parent's {@code _ui} {@code order} / {@code propertyLabels}
 * / {@code propertyDescriptions} entries.
 */
public final class RemoveChildTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private RemoveChildTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("parent_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template or element JSON Schema. Kind is inferred from the @type URI."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key of the child to remove. The tool auto-detects whether the key "
            + "names a field or element child by looking it up in the parent's "
            + "fieldSchemas / elementSchemas."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("parent_json", "key"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("remove_child")
        .title("Remove a field or element child from a template or element")
        .description(
            "Removes a child (field or element) from a CEDAR template or element parent. "
                + "Returns the updated parent JSON, re-validated with CedarValidator."
                + YamlVocabulary.YAML_PREFERRED_DISPLAY_NUDGE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String parentJsonText = stringArg(args, "parent_json");
    if (parentJsonText == null || parentJsonText.isBlank())
      return error("parent_json is required and must not be blank");

    String key = stringArg(args, "key");
    if (key == null || key.isBlank())
      return error("key is required and must not be blank");

    JsonNode parsed;
    try {
      parsed = JACKSON2.readTree(parentJsonText);
    } catch (Exception e) {
      return error("parent_json parse failed: " + e.getMessage());
    }
    if (!(parsed instanceof ObjectNode parentObject))
      return error("parent_json must parse to a JSON object");

    ParentKinds.ParentKind parentKind;
    try {
      parentKind = ParentKinds.detect(parentObject);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    ObjectNode rendered;
    try {
      rendered = switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          TemplateSchemaArtifact updated;
          if (parent.isField(key)) {
            updated = TemplateSchemaArtifact.builder(parent).withoutFieldSchema(key).build();
          } else if (parent.isElement(key)) {
            updated = TemplateSchemaArtifact.builder(parent).withoutElementSchema(key).build();
          } else {
            throw new IllegalArgumentException("template has no child '" + key + "'");
          }
          yield RENDERER.renderTemplateSchemaArtifact(updated);
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          ElementSchemaArtifact updated;
          if (parent.isField(key)) {
            updated = ElementSchemaArtifact.builder(parent).withoutFieldSchema(key).build();
          } else if (parent.isElement(key)) {
            updated = ElementSchemaArtifact.builder(parent).withoutElementSchema(key).build();
          } else {
            throw new IllegalArgumentException("element has no child '" + key + "'");
          }
          yield RENDERER.renderElementSchemaArtifact(updated);
        }
      };
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (ArtifactParseException e) {
      return error("parent JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("remove_child failed: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    try {
      ValidationReport report = switch (parentKind) {
        case TEMPLATE -> VALIDATOR.validateTemplate(rendered);
        case ELEMENT -> VALIDATOR.validateTemplateElement(rendered);
      };
      if (!"true".equals(report.getValidationStatus()))
        return error("updated parent failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating updated parent: " + e.getMessage());
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize updated parent: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static String formatErrors(ValidationReport report)
  {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (ErrorItem err : report.getErrors()) {
      if (i++ > 0) sb.append("; ");
      sb.append(err.toString());
      if (i >= 5) {
        sb.append("; ... (").append(report.getErrors().size() - i).append(" more)");
        break;
      }
    }
    return sb.length() == 0 ? "(no error details)" : sb.toString();
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

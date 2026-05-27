package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifactBuilder;
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

import static org.metadatacenter.artifacts.model.yaml.YamlConstants.FIELD_TYPES;

/**
 * MCP tool {@code add_field} — adds a new field as a child of an existing parent
 * (template or element) JSON Schema artifact.
 *
 * <p>This is the incremental builder counterpart to {@code template_from_yaml}: when
 * YAML can't cleanly cover the authoring path (e.g. mid-pipeline mutation, interleaved
 * terminology MCP calls), this tool grows a template or element field-by-field.
 *
 * <p>Parent kind is inferred from the {@code @type} URI; the result is re-validated with
 * the matching CedarValidator method.
 */
public final class AddFieldTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private AddFieldTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("parent_json", Map.of(
        "type", "string",
        "description",
        "Parent CEDAR template or element JSON Schema. The kind is inferred from the "
            + "artifact's @type URI; both Template and TemplateElement parents are "
            + "supported."));
    properties.put("field_type", Map.of(
        "type", "string",
        "enum", List.copyOf(FIELD_TYPES),
        "description",
        "Kebab-case CEDAR field type for the new child. Same vocabulary as "
            + "'create_field' / 'field_from_yaml' (text-field, controlled-term-field, "
            + "numeric-field, temporal-field, etc.)."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key under which the new field appears in the parent (the JSON Schema "
            + "'properties' map key)."));
    properties.put("name", Map.of(
        "type", "string",
        "description", "Human-readable field name (carried into the parent's _ui propertyLabels)."));
    properties.put("description", Map.of(
        "type", "string",
        "description", "Free-text field description. Optional; defaults to an empty string."));
    properties.put("required", Map.of(
        "type", "boolean",
        "default", Boolean.FALSE,
        "description",
        "Whether the new field is required in instances of the parent. Optional; defaults to false."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("parent_json", "field_type", "key", "name"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_field")
        .title("Add a CEDAR field to a template or element")
        .description(
            "Adds a new field of the requested kebab-case type to an existing CEDAR "
                + "template or element. Parent kind is inferred from the @type URI. "
                + "Returns the updated parent JSON, re-validated with CedarValidator.")
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

    String fieldType = stringArg(args, "field_type");
    if (fieldType == null || fieldType.isBlank())
      return error("field_type is required and must not be blank");
    if (!FIELD_TYPES.contains(fieldType))
      return error("field_type \"" + fieldType + "\" is not a known CEDAR field type. Known: " + FIELD_TYPES);

    String key = stringArg(args, "key");
    if (key == null || key.isBlank())
      return error("key is required and must not be blank");

    String name = stringArg(args, "name");
    if (name == null || name.isBlank())
      return error("name is required and must not be blank");

    String description = stringArgOrDefault(args, "description", "");
    boolean required;
    Object rawRequired = args.get("required");
    if (rawRequired == null) {
      required = false;
    } else if (rawRequired instanceof Boolean b) {
      required = b;
    } else {
      return error("required must be a boolean (got "
          + rawRequired.getClass().getSimpleName() + ")");
    }

    JsonNode parsedParent;
    try {
      parsedParent = JACKSON2.readTree(parentJsonText);
    } catch (Exception e) {
      return error("parent JSON parse failed: " + e.getMessage());
    }
    if (!(parsedParent instanceof ObjectNode parentObject))
      return error("parent_json must parse to a JSON object");

    ParentKinds.ParentKind parentKind;
    try {
      parentKind = ParentKinds.detect(parentObject);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    FieldSchemaArtifact field;
    try {
      FieldSchemaArtifactBuilder<?> fieldBuilder = FieldBuilders.builderFor(fieldType);
      fieldBuilder.withName(name).withDescription(description).withRequiredValue(required);
      field = fieldBuilder.build();
    } catch (RuntimeException e) {
      return error("field build failed: " + e.getMessage());
    }

    ObjectNode rendered;
    try {
      rendered = switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          TemplateSchemaArtifact updated = TemplateSchemaArtifact.builder(parent)
              .withFieldSchema(key, field)
              .build();
          yield RENDERER.renderTemplateSchemaArtifact(updated);
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          ElementSchemaArtifact updated = ElementSchemaArtifact.builder(parent)
              .withFieldSchema(key, field)
              .build();
          yield RENDERER.renderElementSchemaArtifact(updated);
        }
      };
    } catch (ArtifactParseException e) {
      return error("parent JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("add_field failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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

  private static String stringArgOrDefault(Map<String, Object> args, String key, String fallback)
  {
    String value = stringArg(args, key);
    return value == null ? fallback : value;
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

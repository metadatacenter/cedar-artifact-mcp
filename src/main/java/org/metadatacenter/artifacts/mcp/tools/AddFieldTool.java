package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
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
 * MCP tool {@code add_field} — adds an existing field (passed as JSON) as a child
 * of an existing parent (template or element) JSON Schema artifact.
 *
 * <p>Parallel to {@link AddElementTool}: both take a pre-built child JSON rather
 * than building it on the fly. The compose workflow is two-step — {@code create_field}
 * (or {@code field_from_yaml}) returns the child JSON, then {@code add_field} grafts
 * it onto the parent under the supplied key.
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
        "Parent CEDAR template or element JSON Schema. Kind is inferred from the @type URI."));
    properties.put("child_json", Map.of(
        "type", "string",
        "description",
        "Child CEDAR field JSON Schema to add — the kind of JSON 'create_field' or "
            + "'field_from_yaml' returns."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key under which the field appears in the parent (the JSON Schema "
            + "'properties' map key)."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Optional property label override for the parent's _ui block. If omitted, the "
            + "child field's own schema:name is used."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("parent_json", "child_json", "key"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_field")
        .title("Add a CEDAR field to a template or element parent")
        .description(
            "Adds an existing CEDAR field (as JSON) as a child of a CEDAR template or "
                + "element. Parent kind is inferred from its @type URI. Returns the "
                + "updated parent JSON, re-validated with CedarValidator.")
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

    String childJsonText = stringArg(args, "child_json");
    if (childJsonText == null || childJsonText.isBlank())
      return error("child_json is required and must not be blank");

    String key = stringArg(args, "key");
    if (key == null || key.isBlank())
      return error("key is required and must not be blank");

    String nameOverride = stringArg(args, "name");  // optional

    JsonNode parsedParent;
    try {
      parsedParent = JACKSON2.readTree(parentJsonText);
    } catch (Exception e) {
      return error("parent_json parse failed: " + e.getMessage());
    }
    if (!(parsedParent instanceof ObjectNode parentObject))
      return error("parent_json must parse to a JSON object");

    JsonNode parsedChild;
    try {
      parsedChild = JACKSON2.readTree(childJsonText);
    } catch (Exception e) {
      return error("child_json parse failed: " + e.getMessage());
    }
    if (!(parsedChild instanceof ObjectNode childObject))
      return error("child_json must parse to a JSON object");

    ParentKinds.ParentKind parentKind;
    try {
      parentKind = ParentKinds.detect(parentObject);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    FieldSchemaArtifact child;
    try {
      child = READER.readFieldSchemaArtifact(childObject);
    } catch (ArtifactParseException e) {
      return error("child_json rejected by reader (must be a CEDAR field): " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String label = nameOverride == null || nameOverride.isBlank() ? child.name() : nameOverride;

    ObjectNode rendered;
    try {
      rendered = switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          TemplateSchemaArtifact updated = TemplateSchemaArtifact.builder(parent)
              .withFieldSchema(key, child, label)
              .build();
          yield RENDERER.renderTemplateSchemaArtifact(updated);
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          ElementSchemaArtifact updated = ElementSchemaArtifact.builder(parent)
              .withFieldSchema(key, child, label)
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

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

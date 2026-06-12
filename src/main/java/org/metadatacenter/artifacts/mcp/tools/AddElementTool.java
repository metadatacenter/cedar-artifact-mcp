package org.metadatacenter.artifacts.mcp.tools;

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
 * MCP tool {@code add_element} — adds an existing element (passed as YAML) as a child
 * of an existing parent (template or element) artifact.
 *
 * <p>Pairs with {@link AddFieldTool}: this is the "add an entire sub-tree" path where
 * the child element has already been composed (likely by an earlier
 * {@code create_element} call).
 */
public final class AddElementTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private AddElementTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("parent_json", Map.of(
        "type", "string",
        "description",
        "Parent CEDAR template or element as YAML (the exchange form). Kind is inferred "
            + "from the artifact. JSON Schema is also accepted."));
    properties.put("child_json", Map.of(
        "type", "string",
        "description",
        "Child CEDAR element as YAML to add — the kind of artifact 'create_element' "
            + "returns. JSON Schema is also accepted."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key under which the child element appears in the parent (the JSON "
            + "Schema 'properties' map key). Optional; defaults to the child element's "
            + "own schema:name. The library rejects duplicate keys, so supply an "
            + "explicit key when adding two children with the same name."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Optional property label override for the parent's _ui block. If omitted, the "
            + "child element's own schema:name is used."));
    properties.put("description", Map.of(
        "type", "string",
        "description",
        "Optional property description override for the parent's _ui block. If omitted, "
            + "the child element's own schema:description is used."));
    properties.put("isMultiInstance", Map.of(
        "type", "boolean",
        "default", Boolean.FALSE,
        "description",
        "Whether the element appears as a list (array of nested objects) rather than a "
            + "single object in instances of the parent. Optional; defaults to false. "
            + "Overrides whatever isMultiple setting the child JSON already carries — "
            + "this is the per-add-site control, since the same reusable element may be "
            + "single-instance in one parent and multi-instance in another."));
    properties.put("minItems", Map.of(
        "type", "integer",
        "description",
        "Minimum number of instances when isMultiInstance is true. Optional; left unset "
            + "if omitted. Only meaningful for multi-instance elements."));
    properties.put("maxItems", Map.of(
        "type", "integer",
        "description",
        "Maximum number of instances when isMultiInstance is true. Optional; left unset "
            + "if omitted. Only meaningful for multi-instance elements."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("parent_json", "child_json"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_element")
        .title("Add a CEDAR element to a template or element parent")
        .description(
            "Adds an existing CEDAR element (as YAML) as a child of a CEDAR template or "
                + "element. Parent kind is inferred from the artifact. Returns the "
                + "updated parent as expanded YAML, re-validated with CedarValidator."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
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

    String keyArg = stringArg(args, "key");  // optional; defaults to child's schema:name
    String nameOverride = stringArg(args, "name");  // optional
    String descriptionOverride = stringArg(args, "description");  // optional

    boolean isMultiInstance;
    Object rawIsMulti = args.get("isMultiInstance");
    if (rawIsMulti == null) {
      isMultiInstance = false;
    } else if (rawIsMulti instanceof Boolean b) {
      isMultiInstance = b;
    } else {
      return error("isMultiInstance must be a boolean (got "
          + rawIsMulti.getClass().getSimpleName() + ")");
    }

    Integer minItems;
    try {
      minItems = optionalIntArg(args, "minItems");
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }
    Integer maxItems;
    try {
      maxItems = optionalIntArg(args, "maxItems");
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    ObjectNode parentObject;
    try {
      parentObject = ArtifactExchange.toObjectNode(parentJsonText);
    } catch (RuntimeException e) {
      return error("parent parse failed: " + e.getMessage());
    }

    ObjectNode childObject;
    try {
      childObject = ArtifactExchange.toObjectNode(childJsonText);
    } catch (RuntimeException e) {
      return error("child parse failed: " + e.getMessage());
    }

    ParentKinds.ParentKind parentKind;
    try {
      parentKind = ParentKinds.detect(parentObject);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    ElementSchemaArtifact child;
    try {
      ElementSchemaArtifact parsed = READER.readElementSchemaArtifact(childObject);
      // Rebuild the child applying the per-add-site overrides (isMultiInstance plus
      // its companion minItems/maxItems bounds), discarding whatever the child JSON
      // carried for those. The same reusable element can be single- or multi-instance
      // in different parents, with different bounds in each.
      ElementSchemaArtifact.Builder rebuild = ElementSchemaArtifact.builder(parsed)
          .withIsMultiple(isMultiInstance);
      if (minItems != null) rebuild.withMinItems(minItems);
      if (maxItems != null) rebuild.withMaxItems(maxItems);
      child = rebuild.build();
    } catch (ArtifactParseException e) {
      return error("child_json rejected by reader (must be a CEDAR element): " + e.getMessage());
    } catch (RuntimeException e) {
      return error("element reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String key = keyArg == null || keyArg.isBlank() ? child.name() : keyArg;
    String label = nameOverride == null || nameOverride.isBlank() ? child.name() : nameOverride;
    String descriptionLabel = descriptionOverride == null || descriptionOverride.isBlank()
        ? child.description() : descriptionOverride;

    ObjectNode rendered;
    try {
      rendered = switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          TemplateSchemaArtifact updated = TemplateSchemaArtifact.builder(parent)
              .withElementSchema(key, child, label, descriptionLabel)
              .build();
          yield RENDERER.renderTemplateSchemaArtifact(updated);
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          ElementSchemaArtifact updated = ElementSchemaArtifact.builder(parent)
              .withElementSchema(key, child, label, descriptionLabel)
              .build();
          yield RENDERER.renderElementSchemaArtifact(updated);
        }
      };
    } catch (ArtifactParseException e) {
      return error("parent JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("add_element failed: " + e.getMessage());
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

    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated parent as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
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

  /**
   * Read an optional integer argument. JSON-RPC numbers arrive boxed as Integer, Long,
   * or in rare cases a stringified form; coerce them to Integer or fail with a clean
   * message. Returns {@code null} when the argument is absent.
   */
  private static Integer optionalIntArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    if (raw == null) return null;
    if (raw instanceof Integer i) return i;
    if (raw instanceof Long l) {
      if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
        throw new IllegalArgumentException(key + " is out of integer range: " + l);
      return l.intValue();
    }
    if (raw instanceof Number n) return n.intValue();
    throw new IllegalArgumentException(key + " must be an integer (got "
        + raw.getClass().getSimpleName() + ")");
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code replace_element} — replaces the child at {@code key} in a CEDAR
 * template or element with the supplied element, keeping the key and its position in
 * the parent's display order ({@code _ui.order}).
 *
 * <p>Semantically remove_child + add_element, except position-preserving — the library's
 * builders append on add, so the two-step route would move the child to the end of the
 * form. The per-add-site overrides (isMultiInstance, minItems, maxItems) mirror
 * {@link AddElementTool} and apply to the incoming child. The child being replaced may
 * be a field or an element — the slot keeps its key either way.
 */
public final class ReplaceElementTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ReplaceElementTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("parent", Map.of(
        "type", "string",
        "description",
        "Parent CEDAR template or element as YAML (the exchange form). Kind is inferred from "
            + "the artifact. JSON Schema is also accepted."));
    properties.put("child", Map.of(
        "type", "string",
        "description",
        "Replacement CEDAR element as YAML — the kind of artifact 'create_element' returns. "
            + "JSON Schema is also accepted."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key of the existing child to replace. The replacement keeps this key and "
            + "the child's position in the parent's display order."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Optional property label override for the parent's _ui block. If omitted, the "
            + "replacement element's own schema:name is used."));
    properties.put("description", Map.of(
        "type", "string",
        "description",
        "Optional property description override for the parent's _ui block. If omitted, "
            + "the replacement element's own schema:description is used."));
    properties.put("isMultiInstance", Map.of(
        "type", "boolean",
        "default", Boolean.FALSE,
        "description",
        "Whether the element appears as a list (array of nested objects) rather than a "
            + "single nested object in instances of the parent. Optional; defaults to false. Overrides "
            + "whatever isMultiple setting the child JSON already carries — this is the "
            + "per-add-site control, since the same reusable element may be single-instance "
            + "in one parent and multi-instance in another."));
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
    properties.put("property_iri", Map.of(
        "type", "string",
        "description",
        "IRI of the ontology property this element maps to in instances (the JSON-LD "
            + "@context mapping for this key) — what makes instance values real linked "
            + "data rather than plain JSON. Optional; when omitted, whatever the child "
            + "already carries is preserved. Per-add-site, since the same reusable element "
            + "may map to different properties in different parents."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("parent", "child", "key"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("replace_element")
        .title("Replace a child of a template or element with a CEDAR element")
        .description(
            "Replaces the child at 'key' in a CEDAR template or element with the supplied "
                + "element, keeping the key and its position in the display order "
                + "(remove_child + add_element would move it to the end). Returns the updated "
                + "parent as expanded YAML, re-validated with CedarValidator."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String parentJsonText = stringArg(args, "parent");
    if (parentJsonText == null || parentJsonText.isBlank())
      return error("parent is required and must not be blank");

    String childJsonText = stringArg(args, "child");
    if (childJsonText == null || childJsonText.isBlank())
      return error("child is required and must not be blank");

    String key = stringArg(args, "key");
    if (key == null || key.isBlank())
      return error("key is required and must not be blank — it names the child to replace");

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
    URI propertyIri;
    try {
      propertyIri = optionalUriArg(args, "property_iri");
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
      if (propertyIri != null) rebuild.withPropertyUri(propertyIri);
      child = rebuild.build();
    } catch (ArtifactParseException e) {
      return error("child rejected by reader (must be a CEDAR element): " + e.getMessage());
    } catch (RuntimeException e) {
      return error("element reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String label = nameOverride == null || nameOverride.isBlank() ? child.name() : nameOverride;
    String descriptionLabel = descriptionOverride == null || descriptionOverride.isBlank()
        ? child.description() : descriptionOverride;

    ObjectNode rendered;
    try {
      rendered = switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          int position = parent.templateUi().order().indexOf(key);
          TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder(parent);
          if (parent.isField(key)) {
            builder.withoutFieldSchema(key);
          } else if (parent.isElement(key)) {
            builder.withoutElementSchema(key);
          } else {
            throw new IllegalArgumentException("template has no child '" + key + "'");
          }
          TemplateSchemaArtifact updated = builder
              .withElementSchema(key, child, label, descriptionLabel)
              .build();
          ObjectNode node = RENDERER.renderTemplateSchemaArtifact(updated);
          UiOrders.restorePosition(node, key, position);
          yield node;
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          int position = parent.elementUi().order().indexOf(key);
          ElementSchemaArtifact.Builder builder = ElementSchemaArtifact.builder(parent);
          if (parent.isField(key)) {
            builder.withoutFieldSchema(key);
          } else if (parent.isElement(key)) {
            builder.withoutElementSchema(key);
          } else {
            throw new IllegalArgumentException("element has no child '" + key + "'");
          }
          ElementSchemaArtifact updated = builder
              .withElementSchema(key, child, label, descriptionLabel)
              .build();
          ObjectNode node = RENDERER.renderElementSchemaArtifact(updated);
          UiOrders.restorePosition(node, key, position);
          yield node;
        }
      };
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (ArtifactParseException e) {
      return error("parent JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("replace_element failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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

  /** Read an optional URI argument; absent returns {@code null}, malformed fails cleanly. */
  private static URI optionalUriArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    if (raw == null || raw.toString().isBlank()) return null;
    try {
      return new URI(raw.toString());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(key + " is not a valid URI: " + e.getMessage());
    }
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

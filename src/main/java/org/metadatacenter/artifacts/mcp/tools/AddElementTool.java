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
 * MCP tool {@code add_element} — adds an existing element (passed as JSON) as a child
 * of an existing parent (template or element) JSON Schema artifact.
 *
 * <p>Pairs with {@link AddFieldTool}: this is the "add an entire sub-tree" path where
 * the child element has already been composed (likely by an earlier
 * {@code create_element} / {@code element_from_yaml} call).
 */
public final class AddElementTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
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
        "Parent CEDAR template or element JSON Schema. Kind is inferred from the @type URI."));
    properties.put("child_json", Map.of(
        "type", "string",
        "description",
        "Child CEDAR element JSON Schema to add — the kind of JSON 'create_element' "
            + "or 'element_from_yaml' returns."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key under which the child element appears in the parent (the JSON "
            + "Schema 'properties' map key)."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Optional property label override for the parent's _ui block. If omitted, the "
            + "child element's own schema:name is used."));
    properties.put("isMultiInstance", Map.of(
        "type", "boolean",
        "default", Boolean.FALSE,
        "description",
        "Whether the element appears as a list (array of nested objects) rather than a "
            + "single object in instances of the parent. Optional; defaults to false. "
            + "Overrides whatever isMultiple setting the child JSON already carries — "
            + "this is the per-add-site control, since the same reusable element may be "
            + "single-instance in one parent and multi-instance in another."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("parent_json", "child_json", "key"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_element")
        .title("Add a CEDAR element to a template or element parent")
        .description(
            "Adds an existing CEDAR element (as JSON) as a child of a CEDAR template or "
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

    ElementSchemaArtifact child;
    try {
      ElementSchemaArtifact parsed = READER.readElementSchemaArtifact(childObject);
      // Rebuild the child with isMultiInstance applied at this add site, overriding
      // whatever the child JSON carried. The same reusable element can be single- or
      // multi-instance in different parents, so the flag belongs on the add call.
      child = ElementSchemaArtifact.builder(parsed).withIsMultiple(isMultiInstance).build();
    } catch (ArtifactParseException e) {
      return error("child_json rejected by reader (must be a CEDAR element): " + e.getMessage());
    } catch (RuntimeException e) {
      return error("element reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String label = nameOverride == null || nameOverride.isBlank() ? child.name() : nameOverride;

    ObjectNode rendered;
    try {
      rendered = switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          TemplateSchemaArtifact updated = TemplateSchemaArtifact.builder(parent)
              .withElementSchema(key, child, label)
              .build();
          yield RENDERER.renderTemplateSchemaArtifact(updated);
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          ElementSchemaArtifact updated = ElementSchemaArtifact.builder(parent)
              .withElementSchema(key, child, label)
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

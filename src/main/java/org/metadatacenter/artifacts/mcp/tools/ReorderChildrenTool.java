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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool {@code reorder_children} — sets the display order of a template or element
 * parent's children ({@code _ui.order}, the order forms render fields in).
 *
 * <p>Declarative, mirroring {@code set_options}' replace semantics: the call states the
 * complete permutation of the existing child keys every time, and reordering again is
 * just another call. The complete-permutation requirement is load-bearing — the library
 * prunes children absent from the order, so a partial list would silently delete
 * children; requiring exactly the current keys turns that into a clean error. One tool
 * for both child kinds (like {@code remove_child}) — ordering is kind-agnostic, and
 * static fields participate like any other child — moving a section break is ordinary
 * reordering.
 */
public final class ReorderChildrenTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ReorderChildrenTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("parent", Map.of(
        "type", "string",
        "description",
        "CEDAR template or element as YAML (the exchange form). Kind is inferred from "
            + "the artifact. JSON Schema is also accepted."));
    properties.put("keys", Map.of(
        "type", "array",
        "items", Map.of("type", "string"),
        "minItems", 1,
        "description",
        "The parent's child keys in the desired display order — the COMPLETE list, each "
            + "existing key exactly once. A partial list is rejected — omitting a key would "
            + "delete the child, not just leave it unordered."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("parent", "keys"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("reorder_children")
        .title("Reorder the children of a template or element")
        .description(
            "Sets the display order of a CEDAR template or element's children — the order "
                + "forms render fields in. Takes the complete permutation of the existing "
                + "child keys; reordering again is calling again with a different order. "
                + "Returns the updated parent as expanded YAML, re-validated with "
                + "CedarValidator."
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

    List<String> keys = new ArrayList<>();
    Object rawKeys = args.get("keys");
    if (!(rawKeys instanceof List<?> list) || list.isEmpty())
      return error("keys is required and must be a non-empty array of child keys");
    for (Object key : list) {
      if (key == null || key.toString().isBlank())
        return error("keys must not contain blank entries");
      keys.add(key.toString());
    }

    ObjectNode parentObject;
    try {
      parentObject = ArtifactExchange.toObjectNode(parentJsonText);
    } catch (RuntimeException e) {
      return error("parent parse failed: " + e.getMessage());
    }

    ParentKinds.ParentKind parentKind;
    try {
      parentKind = ParentKinds.detect(parentObject);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    List<String> currentOrder;
    ObjectNode rendered;
    try {
      switch (parentKind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact parent = READER.readTemplateSchemaArtifact(parentObject);
          currentOrder = parent.templateUi().order();
          rendered = RENDERER.renderTemplateSchemaArtifact(parent);
        }
        case ELEMENT -> {
          ElementSchemaArtifact parent = READER.readElementSchemaArtifact(parentObject);
          currentOrder = parent.elementUi().order();
          rendered = RENDERER.renderElementSchemaArtifact(parent);
        }
        default -> throw new IllegalStateException("unreachable");
      }
    } catch (ArtifactParseException e) {
      return error("parent JSON rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("reorder_children failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String permutationError = checkPermutation(keys, currentOrder);
    if (permutationError != null)
      return error(permutationError);

    UiOrders.setOrder(rendered, keys);

    try {
      ValidationReport report = switch (parentKind) {
        case TEMPLATE -> VALIDATOR.validateTemplate(rendered);
        case ELEMENT -> VALIDATOR.validateTemplateElement(rendered);
      };
      if (!"true".equals(report.getValidationStatus()))
        return error("reordered parent failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating reordered parent: " + e.getMessage());
    }

    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render reordered parent as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  /**
   * Returns {@code null} when {@code keys} is a permutation of {@code currentOrder},
   * otherwise a caller-facing message. Every message echoes the current order so a
   * mistaken call can be corrected in one round trip.
   */
  private static String checkPermutation(List<String> keys, List<String> currentOrder)
  {
    Set<String> seen = new HashSet<>();
    for (String key : keys)
      if (!seen.add(key))
        return "duplicate key '" + key + "' in keys; current order: " + currentOrder;

    List<String> unknown = new ArrayList<>(keys);
    unknown.removeAll(currentOrder);
    if (!unknown.isEmpty())
      return "unknown keys " + unknown + " — keys must name existing children only; "
          + "current order: " + currentOrder;

    List<String> missing = new ArrayList<>(currentOrder);
    missing.removeAll(keys);
    if (!missing.isEmpty())
      return "keys must be a complete permutation of the current children; missing "
          + missing + " (omitting a key would delete the child, not just leave it "
          + "unordered); current order: " + currentOrder;

    return null;
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

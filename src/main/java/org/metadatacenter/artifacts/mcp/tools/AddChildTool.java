package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code add_child} — adds an existing field or element as a child of an existing
 * parent (template or element). The single composition entry point, pairing with
 * {@code remove_child}: both child kinds share an identical signature, and the kind is declared
 * inside the child artifact itself, so there is nothing for the caller to classify. The handler
 * detects the child's kind and dispatches to the field or element grafting logic
 * ({@link AddFieldTool} / {@link AddElementTool}).
 */
public final class AddChildTool
{
  private AddChildTool() {}

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
        "Child CEDAR field or element as YAML — the kind of artifact 'create_field' or "
            + "'create_element' returns. Kind is inferred from the artifact. JSON Schema is "
            + "also accepted."));
    properties.put("key", Map.of(
        "type", "string",
        "description",
        "Property key under which the child appears in the parent (the JSON Schema "
            + "'properties' map key). Optional; defaults to the child's own schema:name. The "
            + "library rejects duplicate keys, so supply an explicit key when adding two "
            + "children with the same name."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Optional property label override for the parent's _ui block. If omitted, the "
            + "child's own schema:name is used."));
    properties.put("description", Map.of(
        "type", "string",
        "description",
        "Optional property description override for the parent's _ui block. If omitted, "
            + "the child's own schema:description is used."));
    properties.put("isMultiInstance", Map.of(
        "type", "boolean",
        "default", Boolean.FALSE,
        "description",
        "Whether the child appears as a list (array of values) rather than a single "
            + "value in instances of the parent. Optional; defaults to false. Overrides "
            + "whatever isMultiple setting the child artifact already carries — this is the "
            + "per-add-site control, since the same reusable child may be single-instance "
            + "in one parent and multi-instance in another."));
    properties.put("minItems", Map.of(
        "type", "integer",
        "description",
        "Minimum number of instances when isMultiInstance is true. Optional; left unset "
            + "if omitted. Only meaningful for multi-instance children."));
    properties.put("maxItems", Map.of(
        "type", "integer",
        "description",
        "Maximum number of instances when isMultiInstance is true. Optional; left unset "
            + "if omitted. Only meaningful for multi-instance children."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("parent", "child"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_child")
        .title("Add a CEDAR field or element to a template or element parent")
        .description(
            "Adds an existing CEDAR field or element (as YAML) as a child of a CEDAR template "
                + "or element. Both kinds are inferred from the artifacts — nothing to "
                + "classify. Returns the updated parent as expanded YAML, re-validated with "
                + "CedarValidator. The inverse of remove_child."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
    Object rawChild = args.get("child");
    if (rawChild == null || rawChild.toString().isBlank())
      return error("child is required and must not be blank");

    ObjectNode childNode;
    try {
      childNode = ArtifactExchange.toObjectNode(rawChild.toString());
    } catch (RuntimeException e) {
      return error("child parse failed: " + e.getMessage());
    }

    ArtifactKinds.Kind kind = ArtifactKinds.detect(childNode);
    if (kind == ArtifactKinds.Kind.ELEMENT)
      return AddElementTool.handler(exchange, request);
    if (kind == ArtifactKinds.Kind.FIELD)
      return AddFieldTool.handler(exchange, request);
    return error("child must be a CEDAR field or element; detected "
        + (kind == null ? "an unrecognizable artifact" : "a " + kind.name().toLowerCase())
        + " instead");
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

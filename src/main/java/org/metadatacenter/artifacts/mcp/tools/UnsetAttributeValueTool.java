package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code unset_attribute_value} — removes a named entry from an attribute-value field of
 * a template instance. The inverse of {@code set_attribute_value}. Idempotent: removing an
 * attribute that is not present succeeds and leaves the instance (and the now-possibly-empty
 * attribute-value group) intact.
 */
public final class UnsetAttributeValueTool
{
  private UnsetAttributeValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "The CEDAR template the instance is based on (YAML or JSON), used to confirm field_path "
            + "names an attribute-value field."));
    properties.put("instance", Map.of(
        "type", "string",
        "description",
        "The template instance to edit (YAML or JSON)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the attribute-value field (the group), e.g. 'Custom Properties' "
            + "or 'address/Custom Properties' when nested in an element."));
    properties.put("attribute_name", Map.of(
        "type", "string",
        "description",
        "The name of the attribute to remove from that field. Idempotent: a name that is not "
            + "present is a no-op."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("template", "instance", "field_path", "attribute_name"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("unset_attribute_value")
        .title("Remove an attribute from an attribute-value field")
        .description(
            "Removes the named entry from an attribute-value field of a template instance — the "
                + "inverse of set_attribute_value. Idempotent: removing an attribute that is not "
                + "present succeeds. Returns the updated instance as expanded YAML."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String attributeName = AttributeValueEditing.stringArg(args, "attribute_name");
    if (attributeName == null || attributeName.isBlank())
      return AttributeValueEditing.error("attribute_name is required and must not be blank");

    return AttributeValueEditing.apply(
        AttributeValueEditing.stringArg(args, "template"),
        AttributeValueEditing.stringArg(args, "instance"),
        AttributeValueEditing.stringArg(args, "field_path"),
        group -> {
          group.remove(attributeName);
          return group;
        });
  }
}

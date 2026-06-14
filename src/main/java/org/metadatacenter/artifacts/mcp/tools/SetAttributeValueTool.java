package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_attribute_value} — adds (or overwrites) a dynamic name→value entry on an
 * attribute-value field of a template instance. An attribute-value field holds user-named
 * attributes entered at fill time; each value is a literal string ({@code @value}). The attribute
 * value has no IRI form, so there is a single setter (unlike the literal/IRI split for ordinary
 * field values). Remove an entry with {@code unset_attribute_value}.
 */
public final class SetAttributeValueTool
{
  private SetAttributeValueTool() {}

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
        "The template instance to edit (YAML or JSON), e.g. what create_template_instance returns."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the attribute-value field (the group), e.g. 'Custom Properties' "
            + "or 'address/Custom Properties' when nested in an element. Not the attribute name — "
            + "that is the separate attribute_name argument."));
    properties.put("attribute_name", Map.of(
        "type", "string",
        "description",
        "The user-chosen name of the attribute to set within that field (e.g. 'color'). Setting a "
            + "name that already exists overwrites its value. Must not collide with another child "
            + "key of the same parent instance."));
    properties.put("value", Map.of(
        "type", "string",
        "description",
        "The literal (string) value for the attribute, stored as @value."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("template", "instance", "field_path", "attribute_name", "value"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_attribute_value")
        .title("Set an attribute on an attribute-value field")
        .description(
            "Adds or overwrites a dynamic name→value entry on an attribute-value field of a "
                + "template instance: attribute_name is the user-chosen key, value is its literal "
                + "string value. field_path locates the attribute-value field; the attribute name "
                + "is the separate attribute_name argument. Attribute values are literal-only "
                + "(no IRI form). Returns the updated instance as expanded YAML. Remove an entry "
                + "with unset_attribute_value." + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
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

    if (!args.containsKey("value") || args.get("value") == null)
      return AttributeValueEditing.error("value is required");
    String value = args.get("value").toString();

    return AttributeValueEditing.apply(
        AttributeValueEditing.stringArg(args, "template"),
        AttributeValueEditing.stringArg(args, "instance"),
        AttributeValueEditing.stringArg(args, "field_path"),
        group -> {
          group.put(attributeName, AttributeValueEditing.literal(value));
          return group;
        });
  }
}

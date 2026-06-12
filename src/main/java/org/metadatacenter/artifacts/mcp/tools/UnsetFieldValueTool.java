package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code unset_field_value} — clears or deletes a value on a template instance
 * at a slash-separated {@code field_path}. The inverse of the {@code set_*_field_value}
 * pair; one tool rather than a literal/IRI split because unsetting takes no value, so
 * the signature is identical for every field kind.
 *
 * <p>The path decides the operation (see {@link InstanceValueRemover}): a
 * single-instance field path clears the value back to unset, an indexed multi-instance
 * path deletes that entry, an unindexed multi-instance path clears the whole list.
 * Clearing is idempotent. Required fields may be unset — an in-progress instance is
 * allowed to be incomplete, and {@code requiredValue} is enforced by
 * {@code validate_instance}, not mid-edit.
 */
public final class UnsetFieldValueTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private UnsetFieldValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "CEDAR template the instance is based on, as YAML. Used to resolve the "
            + "field_path and decide what unsetting means at the leaf."));
    properties.put("instance", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the kind 'create_instance' returns)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to unset. Same syntax as 'set_literal_field_value'. A "
            + "single-instance field path ('name', 'address/street') clears the value; "
            + "an indexed multi-instance path ('emails[1]', 'addresses[2]') deletes that "
            + "entry and shifts later entries down; an unindexed multi-instance path "
            + "('emails') clears the whole list."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("template", "instance", "field_path"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("unset_field_value")
        .title("Unset a field value on an instance")
        .description(
            "Clears or deletes a value on a CEDAR template instance at a slash-separated "
                + "field_path — the inverse of the set_*_field_value tools, for any field "
                + "kind. A single-instance field path clears the value back to unset; an "
                + "indexed multi-instance path deletes that entry (later entries shift "
                + "down); an unindexed multi-instance path clears the whole list. "
                + "Idempotent: unsetting an already-unset field succeeds. Required fields "
                + "may be unset — requiredValue is enforced by validate_instance, not "
                + "here. Returns the updated instance as expanded YAML."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template is required and must not be blank");

    String instanceJsonText = stringArg(args, "instance");
    if (instanceJsonText == null || instanceJsonText.isBlank())
      return error("instance is required and must not be blank");

    String fieldPath = stringArg(args, "field_path");
    if (fieldPath == null || fieldPath.isBlank())
      return error("field_path is required and must not be blank");

    ObjectNode templateObject;
    try {
      templateObject = ArtifactExchange.toObjectNode(templateJsonText);
    } catch (RuntimeException e) {
      return error("template parse failed: " + e.getMessage());
    }

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("template parse failed: " + e.getMessage());
    }

    ObjectNode instanceObject;
    try {
      instanceObject = ArtifactExchange.toObjectNode(instanceJsonText);
    } catch (RuntimeException e) {
      return error("instance parse failed: " + e.getMessage());
    }

    TemplateInstanceArtifact instance;
    try {
      instance = READER.readTemplateInstanceArtifact(instanceObject);
    } catch (ArtifactParseException e) {
      return error("instance rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("instance parse failed: " + e.getMessage());
    }

    // A YAML instance is sparse — unset fields are omitted. Inflate against the template so
    // the addressed slot exists; that is also what makes clearing idempotent (clearing a
    // never-set field clears the freshly inflated empty slot).
    try {
      instance = InstanceInflater.inflate(template, instance);
    } catch (RuntimeException e) {
      return error("instance does not match template (could not inflate): " + e.getMessage());
    }

    TemplateInstanceArtifact updated;
    try {
      updated = InstanceValueRemover.remove(template, instance, fieldPath);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("unset_field_value failed: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(updated);
    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated instance as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
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

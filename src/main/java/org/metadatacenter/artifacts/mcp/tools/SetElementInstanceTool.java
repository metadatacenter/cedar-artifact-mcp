package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_element_instance} — grafts an element instance (the kind
 * {@code create_element_instance} returns) into a template instance at a slash-separated
 * {@code field_path} naming an element child.
 *
 * <p>This is the instance-side compose step that makes multi-instance elements fillable:
 * {@code create_template_instance} seeds them as empty lists, and the
 * {@code set_*_field_value} walkers require intermediate entries to exist. Appending an
 * element instance here ({@code addresses[N]} with N == current size) creates the entry; its
 * fields are then set with the regular value tools at {@code addresses[N]/...} paths.
 */
public final class SetElementInstanceTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private SetElementInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "CEDAR template the instance is based on, as YAML. Used to resolve the field_path "
            + "and check the leaf names an element child."));
    properties.put("instance", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the kind 'create_template_instance' returns)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the element child. Same syntax as "
            + "'set_literal_field_value', but the leaf must name an element: a "
            + "single-instance path ('address') replaces the element instance; an indexed "
            + "multi-instance path ('addresses[2]') replaces entry 2, or appends when the "
            + "index equals the current list size."));
    properties.put("element_instance", Map.of(
        "type", "string",
        "description",
        "Element instance as YAML — the kind 'create_element_instance' returns "
            + "(type: element-instance). JSON is also accepted."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("template", "instance", "field_path", "element_instance"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_element_instance")
        .title("Set an element instance on a template instance")
        .description(
            "Grafts an element instance (from create_element_instance) into a "
                + "CEDAR template instance at a slash-separated field_path naming an element "
                + "child. A single-instance element path replaces the element instance; an indexed "
                + "multi-instance path replaces that entry, or appends when the index equals "
                + "the current list size — the way to add entries to a multi-instance element. "
                + "Fill the entry's fields afterwards with the set_*_field_value tools at "
                + "'<path>[N]/<field>' paths. Returns the updated instance as expanded YAML."
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

    String entryText = stringArg(args, "element_instance");
    if (entryText == null || entryText.isBlank())
      return error("element_instance is required and must not be blank");

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

    TemplateInstanceArtifact instance;
    try {
      ObjectNode instanceObject = ArtifactExchange.toObjectNode(instanceJsonText);
      instance = READER.readTemplateInstanceArtifact(instanceObject);
    } catch (ArtifactParseException e) {
      return error("instance rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("instance parse failed: " + e.getMessage());
    }

    ElementInstanceArtifact entry;
    try {
      entry = ArtifactExchange.readElementInstance(entryText);
    } catch (ArtifactParseException e) {
      return error("element_instance rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("element_instance parse failed: " + e.getMessage());
    }

    // A YAML instance is sparse — unset slots are omitted. Inflate against the template so
    // the addressed element slot (an empty list for a fresh multi-instance element) exists.
    try {
      instance = InstanceInflater.inflate(template, instance);
    } catch (RuntimeException e) {
      return error("instance does not match template (could not inflate): " + e.getMessage());
    }

    TemplateInstanceArtifact updated;
    try {
      updated = InstanceElementValues.set(template, instance, fieldPath, entry);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("set_element_instance failed: " + e.getClass().getSimpleName()
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

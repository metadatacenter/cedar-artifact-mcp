package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code create_element_instance} — walks an element schema and produces an
 * empty element instance, the element counterpart of
 * {@code create_template_instance}. The result is a standalone artifact
 * ({@code type: element-instance}) meant to be grafted into a template instance with
 * {@code set_element_instance} — most usefully into a multi-instance element list, which
 * is the one slot kind {@code create_template_instance} cannot pre-populate (it has no
 * way to know how many entries an instance will need).
 *
 * <p>No CedarValidator step at creation: validate the element instance against its element with
 * {@code validate_instance_artifact} (which auto-detects the element schema), or in context once
 * attached to a parent instance.
 */
public final class CreateElementInstanceTool
{
  private CreateElementInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("element", Map.of(
        "type", "string",
        "description",
        "CEDAR element as YAML (the kind 'create_element' returns). JSON Schema is also "
            + "accepted. The instance skeleton mirrors this schema's children."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Human-readable name for the element instance. Optional; defaults to the element's own "
            + "schema:name."));
    properties.put("description", Map.of(
        "type", "string",
        "description", "Optional description for the element instance."));
    properties.put("id", Map.of(
        "type", "string",
        "description",
        "Optional @id for the element instance. Omit it and a fresh "
            + "'https://repo.metadatacenter.org/template-element-instances/<uuid>' IRI is "
            + "auto-minted."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("element"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_element_instance")
        .title("Create an empty element instance from a CEDAR element")
        .description(
            "Walks a CEDAR element and produces an empty element instance — the "
                + "element counterpart of create_template_instance. Graft it into a template "
                + "instance with set_element_instance (e.g. to append an entry to a "
                + "multi-instance element list), then fill its fields with the "
                + "set_*_field_value tools. Returns the standalone element instance as expanded "
                + "YAML; validate it against its element with validate_instance_artifact, or in "
                + "context once attached to a parent instance."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String elementText = stringArg(args, "element");
    if (elementText == null || elementText.isBlank())
      return error("element is required and must not be blank");

    String nameOverride = stringArg(args, "name");  // optional
    String description = stringArg(args, "description");  // optional

    URI id;
    String idArg = stringArg(args, "id");
    if (idArg == null || idArg.isBlank()) {
      id = IdMinter.mintElementInstanceId();
    } else {
      try {
        id = new URI(idArg);
      } catch (URISyntaxException e) {
        return error("id is not a valid URI: " + e.getMessage());
      }
    }

    ElementSchemaArtifact element;
    try {
      element = ArtifactExchange.readElement(elementText);
    } catch (ArtifactParseException e) {
      return error("element rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("element parse failed: " + e.getMessage());
    }

    ElementInstanceArtifact skeleton;
    try {
      skeleton = InstanceInflater.emptyElement(element);
    } catch (RuntimeException e) {
      return error("could not build the instance skeleton: " + e.getMessage());
    }

    String name = nameOverride == null || nameOverride.isBlank() ? element.name() : nameOverride;
    ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder(skeleton)
        .withJsonLdId(id)
        .withName(name);
    if (description != null && !description.isBlank())
      builder.withDescription(description);

    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(builder.build());
    } catch (RuntimeException e) {
      return error("failed to render the element instance as YAML: " + e.getMessage());
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

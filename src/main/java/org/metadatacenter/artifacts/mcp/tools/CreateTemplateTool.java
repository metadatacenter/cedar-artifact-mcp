package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.Version;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code create_template} — builds an empty CEDAR template schema artifact
 * with the supplied name, description, version, and status, and returns it as expanded YAML.
 *
 * <p>Expanded YAML is the exchange form threaded between tool calls (DESIGN.md Principle 8):
 * the caller pipes the returned YAML into follow-up tools (e.g. {@code add_field}) to compose
 * larger templates. See DESIGN.md Principle 3 for why the server is stateless.
 */
public final class CreateTemplateTool
{
  private CreateTemplateTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("name", Map.of(
        "type", "string",
        "description", "Human-readable template name (e.g. \"Patient demographics\")."));
    properties.put("description", Map.of(
        "type", "string",
        "description", "Free-text description of the template's purpose. Optional; defaults to an empty string."));
    properties.put("version", Map.of(
        "type", "string",
        "description", "Semantic version string in major.minor.patch form (e.g. \"0.0.1\"). Optional; defaults to 0.0.1."));
    properties.put("status", ArtifactExchange.statusSchemaProperty());
    properties.put("id", Map.of(
        "type", "string",
        "description", "IRI that identifies the template itself (the @id). Optional, and normally "
            + "omitted: CEDAR mints an artifact's identifier when it is created on a server, so a "
            + "template built here carries none until then. Supply one only to repeat an id a "
            + "CEDAR repository already assigned. Must be an absolute IRI."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("name"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_template")
        .title("Create CEDAR template")
        .description(
            "Builds an empty CEDAR template schema artifact with the supplied name, description, "
                + "version, and status. Returns the artifact as expanded YAML — the exchange form threaded "
                + "into follow-up tools (add_field, add_element, ...) to compose larger templates."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String name = stringArg(args, "name");
    if (name == null || name.isBlank())
      return error("name is required and must not be blank");

    String description = stringArgOrDefault(args, "description", "");
    String versionText = stringArgOrDefault(args, "version", "0.0.1");

    Version version;
    try {
      version = Version.fromString(versionText);
    } catch (IllegalArgumentException e) {
      return error("invalid version \"" + versionText + "\": " + e.getMessage());
    }

    Status status;
    try {
      status = ArtifactExchange.readStatus(args);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    String idText = stringArg(args, "id");
    URI id;
    if (idText != null && !idText.isBlank()) {
      try {
        id = new URI(idText);
      } catch (URISyntaxException e) {
        return error("invalid id \"" + idText + "\": not a valid IRI (" + e.getMessage() + ")");
      }
      if (!id.isAbsolute())
        return error("invalid id \"" + idText + "\": an id must be an absolute IRI "
            + "(e.g. https://repo.metadatacenter.org/templates/5c48700a-4163-436d-8daa-95af7311cded)");
    } else {
      // No identifier is invented here: CEDAR mints every identifier when the artifact is created
      // on a server, so an artifact that has not been created yet simply carries none.
      id = null;
    }

    TemplateSchemaArtifact template;
    try {
      template = TemplateSchemaArtifact.builder()
          .withName(name)
          .withDescription(description)
          .withVersion(version)
          .withStatus(status)
          .withJsonLdId(id)
          .build();
    } catch (RuntimeException e) {
      return error("template build failed: " + e.getMessage());
    }

    // Validate (DESIGN.md Principle 6) before returning. ArtifactExchange renders JSON
    // internally and runs CedarValidator; a non-null result is the validator's diagnostics.
    String validationError = ArtifactExchange.validateTemplate(template);
    if (validationError != null)
      return error("rendered template failed CedarValidator: " + validationError);

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null,
            ArtifactExchange.exchangeYaml(template))))
        .isError(false)
        .build();
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static String stringArgOrDefault(Map<String, Object> args, String key, String fallback)
  {
    String value = stringArg(args, key);
    return value == null ? fallback : value;
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

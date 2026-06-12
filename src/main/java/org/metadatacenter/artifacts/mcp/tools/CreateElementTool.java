package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.Version;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code create_element} — element variant of {@code create_template}.
 *
 * <p>Builds an empty CEDAR element schema artifact with the supplied name, description,
 * and version, and returns it as expanded YAML (the exchange form — DESIGN.md Principle 8).
 * Validates with {@link org.metadatacenter.model.validation.CedarValidator} before returning
 * (DESIGN.md Principle 6).
 */
public final class CreateElementTool
{
  private CreateElementTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("name", Map.of(
        "type", "string",
        "description", "Human-readable element name (e.g. \"Address\")."));
    properties.put("description", Map.of(
        "type", "string",
        "description", "Free-text description of the element's purpose. Optional; defaults to an empty string."));
    properties.put("version", Map.of(
        "type", "string",
        "description", "Semantic version string in major.minor.patch form (e.g. \"0.0.1\"). Optional; defaults to 0.0.1."));
    properties.put("status", ArtifactExchange.statusSchemaProperty());
    properties.put("id", Map.of(
        "type", "string",
        "description", "IRI that identifies the element itself (the @id). Optional; if omitted, "
            + "a fresh CEDAR element IRI is auto-minted "
            + "(https://repo.metadatacenter.org/template-elements/<uuid>). Supply one only when you "
            + "have an id assigned by a CEDAR repository. Must be an absolute IRI."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("name"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_element")
        .title("Create CEDAR element")
        .description(
            "Builds an empty CEDAR element schema artifact with the supplied name, description, "
                + "version, and status. Returns the artifact as expanded YAML — the exchange form. Reusable "
                + "elements are first-class CEDAR artifacts; the returned YAML can be added to "
                + "templates (add_element) or composed via other tools. Use 'element_to_json' to "
                + "export the canonical JSON Schema." + ArtifactExchange.STANDALONE_NOTICE
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
            + "(e.g. https://repo.metadatacenter.org/template-elements/5c48700a-4163-436d-8daa-95af7311cded)");
    } else {
      // No caller-supplied id: mint a top-level CEDAR IRI (DESIGN.md Principle 10).
      id = IdMinter.mintElementId();
    }

    ElementSchemaArtifact element;
    try {
      element = ElementSchemaArtifact.builder()
          .withName(name)
          .withDescription(description)
          .withVersion(version)
          .withStatus(status)
          .withJsonLdId(id)
          .build();
    } catch (RuntimeException e) {
      return error("element build failed: " + e.getMessage());
    }

    String validationError = ArtifactExchange.validateElement(element);
    if (validationError != null)
      return error("rendered element failed CedarValidator: " + validationError);

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null,
            ArtifactExchange.exchangeYaml(element))))
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code create_template} — builds an empty CEDAR template schema artifact
 * with the supplied name, description, and version, and returns its JSON serialization.
 *
 * <p>The returned JSON is the complete artifact: callers thread it back into follow-up
 * tools (e.g. {@code add_field}) to compose larger templates. See DESIGN.md Principle 3
 * for why the server is stateless.
 */
public final class CreateTemplateTool
{
  // The artifact library is built on Jackson 2. The MCP SDK uses Jackson 3. They live in
  // separate packages and coexist on the classpath, but the library's renderer returns a
  // Jackson 2 ObjectNode, so we serialize it with a Jackson 2 mapper.
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  // CedarValidator is the same validator the artifact library's own renderer tests use.
  // Running it here turns "the renderer says this template built" into "the canonical
  // validator agrees the rendered JSON is a valid CEDAR template" — a stronger contract
  // for the LLM that consumes the result.
  private static final ModelValidator VALIDATOR = new CedarValidator();

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
    properties.put("id", Map.of(
        "type", "string",
        "description", "IRI that identifies the template itself (the @id). Optional; if omitted, "
            + "a fresh CEDAR template IRI is auto-minted "
            + "(https://repo.metadatacenter.org/templates/<uuid>). Supply one only when you have an "
            + "id assigned by a CEDAR repository. Must be an absolute IRI."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("name"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_template")
        .title("Create CEDAR template")
        .description(
            "Builds an empty CEDAR template schema artifact with the supplied name, description, "
                + "and version. Returns the artifact serialized as JSON. The caller threads the "
                + "returned JSON back into follow-up tools (add_field, add_element, validate, ...) "
                + "to compose larger templates."
                + YamlVocabulary.YAML_PREFERRED_DISPLAY_NUDGE)
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

    String idText = stringArg(args, "id");
    URI id = null;
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
      // No caller-supplied id: mint a top-level CEDAR IRI (DESIGN.md Principle 10).
      id = IdMinter.mintTemplateId();
    }

    TemplateSchemaArtifact template;
    try {
      TemplateSchemaArtifact.Builder builder = TemplateSchemaArtifact.builder()
          .withName(name)
          .withDescription(description)
          .withVersion(version)
          .withJsonLdId(id);
      template = builder.build();
    } catch (RuntimeException e) {
      return error("template build failed: " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateSchemaArtifact(template);

    // Mirror the artifact library's own renderer-test invariant: every rendered template
    // must pass CedarValidator before we hand it to the LLM. If this ever fails it means
    // the library produced something the canonical validator rejects — a library-side
    // regression we want to surface, not paper over.
    try {
      ValidationReport report = VALIDATOR.validateTemplate(rendered);
      if (!"true".equals(report.getValidationStatus()))
        return error("rendered template failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating rendered template: " + e.getMessage());
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered template: " + e.getMessage());
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

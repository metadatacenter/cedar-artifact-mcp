package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code field_to_json} — field variant of {@code template_to_json}.
 *
 * <p>Takes a CEDAR field described in YAML and returns the canonical CEDAR JSON Schema
 * for a field artifact. Validates the rendered JSON with
 * {@link CedarValidator#validateTemplateField} before returning (DESIGN.md Principle 6).
 */
public final class FieldToJsonTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  // Compact-mode reader — see TemplateToJsonTool for rationale.
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private FieldToJsonTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "CEDAR field described in the artifact library's YAML format. The 'type:' "
            + "discriminator selects the field kind; per-field-type keys live at the "
            + "top level of the same YAML document. Full key vocabulary:\n\n"
            + YamlVocabulary.fieldOnlyVocabulary()));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("field_to_json")
        .title("CEDAR field: YAML → JSON Schema")
        .description(
            "Compiles a CEDAR field described in YAML into the canonical CEDAR JSON "
                + "Schema for a field artifact. The returned JSON has been round-tripped "
                + "through the artifact library's reader/renderer and accepted by "
                + "CedarValidator.validateTemplateField, so a non-error result is a "
                + "guaranteed-valid CEDAR field. Use this to export the canonical JSON Schema "
                + "for cedar-server and other downstream consumers.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawYaml = args.get("artifact");
    if (rawYaml == null)
      return error("artifact argument is required");
    String yamlText = rawYaml.toString();
    if (yamlText.isBlank())
      return error("artifact argument must not be blank");

    LinkedHashMap<String, Object> yamlMap;
    try {
      yamlMap = ArtifactExchange.parseYamlMap(yamlText);
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

    // Mint a top-level @id when the YAML omits one (DESIGN.md Principle 10). A standalone
    // field is a first-class, reusable CEDAR artifact, so it gets an id like any other root.
    Object suppliedId = yamlMap.get(YamlConstants.ID);
    if (suppliedId == null || suppliedId.toString().isBlank())
      yamlMap.put(YamlConstants.ID, IdMinter.mintFieldId().toString());

    FieldSchemaArtifact field;
    try {
      field = READER.readFieldSchemaArtifact(yamlMap);
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderFieldSchemaArtifact(field);
    try {
      ValidationReport report = VALIDATOR.validateTemplateField(rendered);
      if (!"true".equals(report.getValidationStatus()))
        return error("rendered field failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating rendered field: "
          + e.getMessage());
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered field: " + e.getMessage());
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

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

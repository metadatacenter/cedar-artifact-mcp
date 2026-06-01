package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code validate_artifact} — validates a CEDAR artifact of <em>unknown</em> kind by
 * detecting whether it's a template, element, or field (from its {@code @type}) and dispatching to
 * the matching {@link CedarValidator} method. The "from the wild, don't-know-the-kind" entry point.
 *
 * <p>Instances are detected (by {@code schema:isBasedOn}) but not validated here: an instance can
 * only be validated against the template it is based on, so the caller is redirected to
 * {@code validate_instance}. Same JSON-as-is / YAML-via-library contract and report shape as the
 * per-type validators (see {@link ValidateTemplateTool}).
 */
public final class ValidateArtifactTool
{
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ValidateArtifactTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR artifact of unknown kind, as JSON Schema or YAML (auto-detected). The kind "
            + "(template / element / field) is detected from its @type and validated with the "
            + "matching validator. JSON is validated exactly as received."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_artifact")
        .title("Validate a CEDAR artifact (auto-detect kind)")
        .description(
            "Validates a CEDAR template, element, or field without being told which it is — the "
                + "kind is detected from the artifact's @type and dispatched to the right "
                + "validator. Use this for artifacts from the wild when you don't know the kind. "
                + "Accepts JSON Schema (validated as-is) or YAML. Returns {\"valid\": true} or "
                + "{\"valid\": false, \"errors\": [...]} (a non-error result either way). A "
                + "template instance is detected but must be validated with validate_instance "
                + "(which also needs its template).")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String text = stringArg(args, "artifact");
    if (text == null || text.isBlank())
      return error("artifact is required and must not be blank");

    ObjectNode node;
    try {
      node = ArtifactExchange.toObjectNode(text);
    } catch (RuntimeException e) {
      return error("artifact could not be parsed as JSON or YAML: " + e.getMessage());
    }

    ArtifactKinds.Kind kind = ArtifactKinds.detect(node);
    if (kind == null)
      return error("could not determine the artifact kind from its @type — pass it to the "
          + "specific tool instead (validate_template / validate_element / validate_field, or "
          + "validate_instance for an instance)");
    if (kind == ArtifactKinds.Kind.INSTANCE)
      return error("this is a template instance — use validate_instance, which validates it "
          + "against the template it is based on");

    ValidationReport report;
    try {
      report = switch (kind) {
        case TEMPLATE -> VALIDATOR.validateTemplate(node);
        case ELEMENT -> VALIDATOR.validateTemplateElement(node);
        case FIELD -> VALIDATOR.validateTemplateField(node);
        case INSTANCE -> throw new IllegalStateException("unreachable");
      };
    } catch (Exception e) {
      return error("CedarValidator threw while validating " + kind.name().toLowerCase()
          + ": " + e.getMessage());
    }

    return success(ArtifactExchange.validationReportJson(report));
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static McpSchema.CallToolResult success(String json)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

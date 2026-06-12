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
 * MCP tool {@code validate_template} — validates a standalone CEDAR template against the CEDAR
 * model schema using {@link CedarValidator#validateTemplate}.
 *
 * <p>Built for checking artifacts obtained from the wild: a JSON Schema artifact is validated
 * exactly as received (no round-trip through the library reader/renderer, so the verdict reflects
 * the artifact itself, not our library's round-trip fidelity); YAML is read through the library
 * first since the validator only speaks JSON. The verdict is returned as a report
 * ({@code {"valid": ...}}), not a tool error (DESIGN.md Principle 5).
 */
public final class ValidateTemplateTool
{
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ValidateTemplateTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR template as JSON Schema (the canonical form CEDAR servers and the *_to_json exports produce) "
            + "or as YAML; the format is auto-detected. JSON is validated exactly as received — "
            + "use this to check a template obtained from the wild. YAML is read through the "
            + "library first."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_template")
        .title("Validate a CEDAR template")
        .description(
            "Validates a standalone CEDAR template with CedarValidator.validateTemplate. Accepts "
                + "JSON Schema (validated as-is) or YAML. Returns {\"valid\": true} on success, or "
                + "{\"valid\": false, \"errors\": [...]} with the validator's diagnostics — a "
                + "non-error result either way, so read the verdict from the report. For an "
                + "element or field use validate_element / validate_field; for a template "
                + "instance use validate_instance; or use validate_artifact to auto-detect."
                + ArtifactExchange.VERBATIM_INPUT_NOTICE)
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
    if (kind != null && kind != ArtifactKinds.Kind.TEMPLATE)
      return error("this artifact looks like a " + kind.name().toLowerCase()
          + " — use " + kind.tool + " instead");

    ValidationReport report;
    try {
      report = VALIDATOR.validateTemplate(node);
    } catch (Exception e) {
      return error("CedarValidator threw while validating template: " + e.getMessage());
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

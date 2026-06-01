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
 * MCP tool {@code validate_element} — validates a standalone CEDAR element against the CEDAR
 * model schema using {@link CedarValidator#validateTemplateElement}. See {@link ValidateTemplateTool}
 * for the shared contract (JSON validated as-is, YAML read through the library, verdict returned
 * as a report).
 */
public final class ValidateElementTool
{
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ValidateElementTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR element as JSON Schema (the canonical form cedar-server and exports produce) or "
            + "as YAML; the format is auto-detected. JSON is validated exactly as received — use "
            + "this to check an element obtained from the wild. YAML is read through the library "
            + "first."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_element")
        .title("Validate a CEDAR element")
        .description(
            "Validates a standalone CEDAR element with CedarValidator.validateTemplateElement. "
                + "Accepts JSON Schema (validated as-is) or YAML. Returns {\"valid\": true} on "
                + "success, or {\"valid\": false, \"errors\": [...]} with the validator's "
                + "diagnostics — a non-error result either way, so read the verdict from the "
                + "report. For a template or field use validate_template / validate_field; for a "
                + "template instance use validate_instance; or use validate_artifact to "
                + "auto-detect." + ArtifactExchange.VERBATIM_INPUT_NOTICE)
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
    if (kind != null && kind != ArtifactKinds.Kind.ELEMENT)
      return error("this artifact looks like a " + kind.name().toLowerCase()
          + " — use " + kind.tool + " instead");

    ValidationReport report;
    try {
      report = VALIDATOR.validateTemplateElement(node);
    } catch (Exception e) {
      return error("CedarValidator threw while validating element: " + e.getMessage());
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

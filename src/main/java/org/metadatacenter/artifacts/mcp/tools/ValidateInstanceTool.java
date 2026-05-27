package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code validate_instance} — runs an instance JSON through
 * {@link CedarValidator#validateTemplateInstance}, the same canonical validator that
 * the artifact library's own renderer tests use.
 *
 * <p>The instance's required fields, value types, and structural conformance to the
 * template are all checked. Returns a structured report — {@code "valid": true} when
 * the instance passes, or {@code "valid": false} with a list of error messages.
 */
public final class ValidateInstanceTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ValidateInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template JSON Schema (the kind 'template_from_yaml' or 'create_template' "
            + "returns). The instance is validated against this schema."));
    properties.put("instance_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance JSON (the kind 'instance_from_yaml' returns, or what "
            + "a CEDAR repository serves for a saved instance)."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("template_json", "instance_json"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_instance")
        .title("Validate a CEDAR instance against its template")
        .description(
            "Validates a CEDAR template instance against its template using "
                + "CedarValidator.validateTemplateInstance. Returns a structured report: "
                + "{\"valid\": true} on success, or {\"valid\": false, \"errors\": [...]} "
                + "with the validator's diagnostics on failure.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template_json");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template_json is required and must not be blank");

    String instanceJsonText = stringArg(args, "instance_json");
    if (instanceJsonText == null || instanceJsonText.isBlank())
      return error("instance_json is required and must not be blank");

    JsonNode templateNode;
    try {
      templateNode = JACKSON2.readTree(templateJsonText);
    } catch (Exception e) {
      return error("template_json parse failed: " + e.getMessage());
    }

    JsonNode instanceNode;
    try {
      instanceNode = JACKSON2.readTree(instanceJsonText);
    } catch (Exception e) {
      return error("instance_json parse failed: " + e.getMessage());
    }

    ValidationReport report;
    try {
      report = VALIDATOR.validateTemplateInstance(instanceNode, templateNode);
    } catch (Exception e) {
      return error("CedarValidator threw while validating instance: " + e.getMessage());
    }

    boolean valid = "true".equals(report.getValidationStatus());
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("valid", valid);
    if (!valid) {
      List<String> errors = new java.util.ArrayList<>();
      for (ErrorItem err : report.getErrors())
        errors.add(err.toString());
      result.put("errors", errors);
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    } catch (Exception e) {
      return error("failed to serialize validation report: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
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

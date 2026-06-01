package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
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
  private static final ModelValidator VALIDATOR = new CedarValidator();
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();

  private ValidateInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template as YAML (the kind 'create_template' returns). The instance is "
            + "validated against this template. JSON Schema is also accepted."));
    properties.put("instance_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the kind 'create_instance' returns, or what "
            + "a CEDAR repository serves for a saved instance). JSON is also accepted."));

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

    ObjectNode templateNode;
    try {
      templateNode = ArtifactExchange.toObjectNode(templateJsonText);
    } catch (RuntimeException e) {
      return error("template_json parse failed: " + e.getMessage());
    }

    // The instance YAML is sparse (unset fields omitted). CedarValidator checks the JSON form,
    // whose "every field present" rule is a JSON-Schema concern — so inflate the instance against
    // the template (re-adding the empty slots the JSON form requires) before validating. If the
    // instance can't be parsed/inflated as a CEDAR instance (malformed, missing structural keys),
    // validate it in its raw form so the validator's diagnostics come back as a normal
    // {"valid": false, ...} report rather than a tool error.
    ObjectNode instanceNode;
    try {
      TemplateSchemaArtifact template = ArtifactExchange.readTemplate(templateJsonText);
      TemplateInstanceArtifact sparse = ArtifactExchange.readInstance(instanceJsonText);
      instanceNode = JSON_RENDERER.renderTemplateInstanceArtifact(InstanceInflater.inflate(template, sparse));
    } catch (RuntimeException inflateFailed) {
      try {
        instanceNode = ArtifactExchange.toObjectNode(instanceJsonText);
      } catch (RuntimeException e) {
        return error("instance_json parse failed: " + e.getMessage());
      }
    }

    ValidationReport report;
    try {
      report = VALIDATOR.validateTemplateInstance(instanceNode, templateNode);
    } catch (Exception e) {
      return error("CedarValidator threw while validating instance: " + e.getMessage());
    }

    String json;
    try {
      json = ArtifactExchange.validationReportJson(report);
    } catch (RuntimeException e) {
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

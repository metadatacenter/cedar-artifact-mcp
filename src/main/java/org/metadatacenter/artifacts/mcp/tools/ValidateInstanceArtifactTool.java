package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code validate_instance_artifact} — validates a CEDAR instance against the schema
 * artifact it is based on. The schema's kind is auto-detected from its {@code @type}: a template
 * is validated with {@link CedarValidator#validateTemplateInstance}, an element with
 * {@link CedarValidator#validateElementInstance} (a CEDAR element artifact <em>is</em> the JSON
 * Schema that validates its instances in situ). The instance's required fields, value types, and
 * structural conformance to the schema are all checked.
 *
 * <p>The exchange YAML is sparse (unset fields omitted), while the JSON shape the validator checks
 * requires every slot present — so the instance is inflated against its schema before validating.
 * If it can't be parsed/inflated as an instance (malformed, missing structural keys), it is
 * validated in its raw form so the validator's diagnostics come back as a normal
 * {@code {"valid": false, ...}} report rather than a tool error. For an element schema the instance
 * is checked in its <em>nested</em> shape — as it would sit inside a parent instance — so the
 * standalone document's identity keys ({@code schema:name}, {@code schema:description}) are dropped
 * before validating. Returns a structured report ({@code {"valid": ...}}), not a tool error
 * (DESIGN.md Principle 5).
 */
public final class ValidateInstanceArtifactTool
{
  private static final ModelValidator VALIDATOR = new CedarValidator();
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();

  private ValidateInstanceArtifactTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("schema_artifact", Map.of(
        "type", "string",
        "description",
        "The CEDAR schema to validate against — a template (the kind 'create_template' returns) "
            + "or an element (the kind 'create_element' returns); the kind is auto-detected from "
            + "its @type. YAML or JSON Schema."));
    properties.put("instance_artifact", Map.of(
        "type", "string",
        "description",
        "The instance to validate: a template instance (the kind 'create_template_instance' "
            + "returns, or what a CEDAR repository serves) when the schema is a template, or an "
            + "element instance (the kind 'create_element_instance' returns) when it is an "
            + "element. YAML or JSON."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("schema_artifact", "instance_artifact"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_instance_artifact")
        .title("Validate a CEDAR instance against its schema (auto-detect template/element)")
        .description(
            "Validates a CEDAR template instance or element instance against the schema artifact "
                + "it is based on, using the canonical CedarValidator. The schema kind is "
                + "auto-detected from its @type — a template runs validateTemplateInstance, an "
                + "element runs validateElementInstance (an element is itself the JSON Schema its "
                + "instances validate against, so the element instance is checked in its nested "
                + "shape). Accepts YAML or JSON for both arguments. Returns {\"valid\": true} on "
                + "success, or {\"valid\": false, \"errors\": [...]} with the validator's "
                + "diagnostics — a non-error result either way, so read the verdict from the "
                + "report. (To validate a standalone template, element, or field — a schema, not "
                + "an instance — use validate_schema_artifact.)")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String schemaText = stringArg(args, "schema_artifact");
    if (schemaText == null || schemaText.isBlank())
      return error("schema_artifact is required and must not be blank");

    String instanceText = stringArg(args, "instance_artifact");
    if (instanceText == null || instanceText.isBlank())
      return error("instance_artifact is required and must not be blank");

    ObjectNode schemaNode;
    try {
      schemaNode = ArtifactExchange.toObjectNode(schemaText);
    } catch (RuntimeException e) {
      return error("schema_artifact parse failed: " + e.getMessage());
    }

    ArtifactKinds.Kind kind = ArtifactKinds.detect(schemaNode);
    if (kind == ArtifactKinds.Kind.TEMPLATE)
      return validateTemplateInstance(schemaText, schemaNode, instanceText);
    if (kind == ArtifactKinds.Kind.ELEMENT)
      return validateElementInstance(schemaText, schemaNode, instanceText);

    return error("schema_artifact must be a CEDAR template or element — an instance is validated "
        + "against the template or element it is based on (got "
        + (kind == null ? "an artifact whose kind could not be determined from its @type"
            : "a " + kind.name().toLowerCase()) + ")");
  }

  private static McpSchema.CallToolResult validateTemplateInstance(
      String templateText, ObjectNode templateNode, String instanceText)
  {
    ObjectNode instanceNode;
    try {
      TemplateSchemaArtifact template = ArtifactExchange.readTemplate(templateText);
      TemplateInstanceArtifact sparse = ArtifactExchange.readInstance(instanceText);
      instanceNode = JSON_RENDERER.renderTemplateInstanceArtifact(InstanceInflater.inflate(template, sparse));
    } catch (RuntimeException inflateFailed) {
      try {
        instanceNode = ArtifactExchange.toObjectNode(instanceText);
      } catch (RuntimeException e) {
        return error("instance_artifact parse failed: " + e.getMessage());
      }
    }

    ValidationReport report;
    try {
      report = VALIDATOR.validateTemplateInstance(instanceNode, templateNode);
    } catch (Exception e) {
      return error("CedarValidator threw while validating instance: " + e.getMessage());
    }
    return report(report);
  }

  private static McpSchema.CallToolResult validateElementInstance(
      String elementText, ObjectNode elementSchemaNode, String instanceText)
  {
    ObjectNode entryNode;
    try {
      ElementSchemaArtifact element = ArtifactExchange.readElement(elementText);
      ElementInstanceArtifact sparse = ArtifactExchange.readElementInstance(instanceText);
      entryNode = JSON_RENDERER.renderElementInstanceArtifact(
          InstanceInflater.inflateElement(element, sparse));
    } catch (RuntimeException inflateFailed) {
      try {
        entryNode = ArtifactExchange.toObjectNode(instanceText);
      } catch (RuntimeException e) {
        return error("instance_artifact parse failed: " + e.getMessage());
      }
    }

    // Nested shape: the standalone document's identity keys don't exist inside a parent.
    entryNode.remove("schema:name");
    entryNode.remove("schema:description");

    ValidationReport report;
    try {
      report = VALIDATOR.validateElementInstance(entryNode, elementSchemaNode);
    } catch (Exception e) {
      return error("CedarValidator threw while validating element instance: " + e.getMessage());
    }
    return report(report);
  }

  private static McpSchema.CallToolResult report(ValidationReport report)
  {
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

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
 * MCP tool {@code validate_schema_artifact} — validates a CEDAR <em>schema</em> artifact
 * (template, element, or field) of unknown kind by detecting which it is from its {@code @type}
 * and dispatching to the matching {@link CedarValidator} method. The "from the wild,
 * don't-know-the-kind" entry point. (Instances are not schema artifacts — hence the name; they go
 * through {@code validate_instance_artifact}.)
 *
 * <p>Instances are detected (by {@code schema:isBasedOn}) but not validated here: an instance can
 * only be validated against the template it is based on, so the caller is redirected to
 * {@code validate_instance_artifact}. JSON is validated exactly as received (no round-trip through the
 * library reader/renderer, so the verdict reflects the artifact itself, not our library's
 * round-trip fidelity); YAML is read through the library first since the validator only speaks
 * JSON. The verdict is returned as a report ({@code {"valid": ...}}), not a tool error
 * (DESIGN.md Principle 5).
 */
public final class ValidateSchemaArtifactTool
{
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ValidateSchemaArtifactTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR schema artifact of unknown kind, as JSON Schema or YAML (auto-detected). The "
            + "kind (template / element / field) is detected from its @type and validated with "
            + "the matching validator. JSON is validated exactly as received."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_schema_artifact")
        .title("Validate a CEDAR schema artifact (auto-detect kind)")
        .description(
            "Validates a standalone CEDAR template, element, or field against the CEDAR model "
                + "schema — built for checking artifacts from the wild (fetched from a server or "
                + "sent by a colleague). The kind is detected from the artifact's @type and "
                + "dispatched to the right validator, so you need not say which it is. Accepts "
                + "JSON Schema (validated exactly as received) or YAML (read through the library "
                + "first). Returns {\"valid\": true} or {\"valid\": false, \"errors\": [...]} — a "
                + "non-error result either way, so read the verdict from the report. A template "
                + "instance is detected but must be validated with validate_instance_artifact "
                + "(which also needs its template)." + ArtifactExchange.VERBATIM_INPUT_NOTICE)
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
      return error("could not determine the artifact kind from its @type — expected a template, "
          + "element, or field (a template instance, identified by schema:isBasedOn, goes through "
          + "validate_instance_artifact)");
    if (kind == ArtifactKinds.Kind.INSTANCE)
      return error("this is a template instance — use validate_instance_artifact, which validates "
          + "it against the template it is based on");

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

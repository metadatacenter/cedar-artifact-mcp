package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.CedarValidationReport;
import org.metadatacenter.model.validation.report.ErrorItem;
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
 * <p>Instances are detected (by {@code schema:isBasedOn}) but not validated here — an instance can
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
  private static final JsonArtifactReader READER = new JsonArtifactReader();

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
                + "JSON Schema (validated exactly as received) or YAML. Either way the artifact "
                + "library must also be able to read it, as the CEDAR server requires before it "
                + "stores one, so the same artifact gets the same verdict in either form. Returns "
                + "{\"valid\": true} or {\"valid\": false, \"errors\": [...]} — a "
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

    // Syntax first: an artifact that is not JSON or YAML at all is an error, not a verdict.
    boolean json = ArtifactExchange.looksLikeJson(text);
    ObjectNode node;
    try {
      if (json) {
        node = ArtifactExchange.asObjectNode(text);
      } else {
        Object type = ArtifactExchange.parseYamlMap(text).get("type");
        if ("instance".equals(type) || "element-instance".equals(type))
          return error("this is an instance — use validate_instance_artifact, which validates it "
              + "against the template it is based on");
        node = null;
      }
    } catch (RuntimeException e) {
      return error("artifact could not be parsed as JSON or YAML: " + e.getMessage());
    }
    // The YAML is read through the library on the way to JSON, so a refusal there is the verdict.
    if (!json) {
      try {
        node = ArtifactExchange.toObjectNode(text);
      } catch (RuntimeException refused) {
        return success(ArtifactExchange.validationReportJson(
            withReaderRefusal(CedarValidationReport.newEmptyReport(), refused)));
      }
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

    // JSON reaches the validator as received, so the reader is asked separately, as the server asks
    // it. Without this a JSON artifact passed where the same artifact in YAML was refused.
    if (json) {
      try {
        switch (kind) {
          case TEMPLATE -> READER.readTemplateSchemaArtifact(node.deepCopy());
          case ELEMENT -> READER.readElementSchemaArtifact(node.deepCopy());
          case FIELD -> READER.readFieldSchemaArtifact(node.deepCopy());
          case INSTANCE -> throw new IllegalStateException("unreachable");
        }
      } catch (RuntimeException refused) {
        report = withReaderRefusal(report, refused);
      }
    }

    return success(ArtifactExchange.validationReportJson(report));
  }

  /** The report, with the artifact library's refusal to read the artifact added as an error. */
  private static ValidationReport withReaderRefusal(ValidationReport report, RuntimeException refused)
  {
    CedarValidationReport combined = CedarValidationReport.newEmptyReport();
    report.getErrors().forEach(combined::addError);
    report.getWarnings().forEach(combined::addWarning);
    combined.addError(new ErrorItem("The CEDAR artifact library cannot read this artifact, so nothing "
        + "could open it once stored: " + refused.getMessage()));
    return combined;
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code validate_element_instance} — validates an element-instance sub-record
 * against its element. A CEDAR element artifact <em>is</em> the JSON Schema that
 * validates its instances in situ, so this is the same
 * {@link CedarValidator#validateTemplateInstance} call the template-level tool makes,
 * with the element schema as the schema document.
 *
 * <p>The sub-record is validated in its <em>nested</em> shape — as it would sit inside a
 * parent instance: the standalone document's identity keys ({@code schema:name},
 * {@code schema:description}) are not part of that shape ({@code additionalProperties:
 * false} in the element schema) and are dropped before validating.
 */
public final class ValidateElementInstanceTool
{
  private static final ModelValidator VALIDATOR = new CedarValidator();
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();

  private ValidateElementInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("element", Map.of(
        "type", "string",
        "description",
        "CEDAR element as YAML (the kind 'create_element' returns). The sub-record is "
            + "validated against this element. JSON Schema is also accepted."));
    properties.put("element_instance", Map.of(
        "type", "string",
        "description",
        "Element-instance sub-record as YAML — the kind 'create_element_instance' returns "
            + "(type: element-instance). JSON is also accepted."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("element", "element_instance"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("validate_element_instance")
        .title("Validate an element instance against its element")
        .description(
            "Validates an element-instance sub-record against its element using the "
                + "canonical CedarValidator (an element is itself the JSON Schema its "
                + "instances validate against). The sub-record is checked in its nested "
                + "shape — exactly as it would sit inside a parent instance. Returns a "
                + "structured report: {\"valid\": true} on success, or {\"valid\": false, "
                + "\"errors\": [...]} with the validator's diagnostics on failure.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String elementText = stringArg(args, "element");
    if (elementText == null || elementText.isBlank())
      return error("element is required and must not be blank");

    String entryText = stringArg(args, "element_instance");
    if (entryText == null || entryText.isBlank())
      return error("element_instance is required and must not be blank");

    ObjectNode elementSchemaNode;
    try {
      elementSchemaNode = ArtifactExchange.toObjectNode(elementText);
    } catch (RuntimeException e) {
      return error("element parse failed: " + e.getMessage());
    }

    // The exchange YAML is sparse (unset fields omitted); the JSON shape the validator checks
    // requires every slot present, so inflate against the element first. If the sub-record
    // can't be parsed/inflated as an element instance, validate it in its raw form so the
    // validator's diagnostics come back as a normal {"valid": false, ...} report rather than
    // a tool error.
    ObjectNode entryNode;
    try {
      ElementSchemaArtifact element = ArtifactExchange.readElement(elementText);
      ElementInstanceArtifact sparse = ArtifactExchange.readElementInstance(entryText);
      entryNode = JSON_RENDERER.renderElementInstanceArtifact(
          InstanceInflater.inflateElement(element, sparse));
    } catch (RuntimeException inflateFailed) {
      try {
        entryNode = ArtifactExchange.toObjectNode(entryText);
      } catch (RuntimeException e) {
        return error("element_instance parse failed: " + e.getMessage());
      }
    }

    // Nested shape: the standalone document's identity keys don't exist inside a parent.
    entryNode.remove("schema:name");
    entryNode.remove("schema:description");

    ValidationReport report;
    try {
      report = VALIDATOR.validateTemplateInstance(entryNode, elementSchemaNode);
    } catch (Exception e) {
      return error("CedarValidator threw while validating element instance: " + e.getMessage());
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_options} — replaces the literal option list of a choice field (radio,
 * checkbox, single- or multi-select list). The constraints counterpart to the
 * {@code set_*_constraint} tools: those bind a field to <em>ontology</em> values, this binds it
 * to <em>literal</em> values.
 *
 * <p>Replace semantics: the call states the complete option list in display order, and whatever
 * literals the field carried are replaced — declarative and idempotent, so reordering is just
 * calling again with the new order.
 */
public final class SetOptionsTool
{
  private static final ObjectMapper JACKSON = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();

  private SetOptionsTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field", Map.of(
        "type", "string",
        "description",
        "CEDAR choice field (radio-field, checkbox-field, single-select-list-field, "
            + "multi-select-list-field) as YAML — the kind 'create_field' returns. JSON Schema "
            + "is also accepted."));
    properties.put("options", Map.of(
        "type", "array",
        "items", Map.of("type", "string"),
        "minItems", 1,
        "description",
        "The complete option list, in display order. REPLACES whatever options the field "
            + "already carries — state the full list every time; reordering is calling again "
            + "with the new order."));
    properties.put("default_option", Map.of(
        "type", "string",
        "description",
        "Optionally names the one option that is pre-selected (selectedByDefault). Must be a "
            + "member of options. Omit for no pre-selection."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("field", "options"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_options")
        .title("Set the option list of a radio / checkbox / list field")
        .description(
            "Replaces the literal option list of a choice field — radio, checkbox, or "
                + "single-/multi-select list. Takes the complete list in display order; "
                + "default_option optionally marks one option as pre-selected. For "
                + "ontology-backed values use the set_*_constraint tools instead. Returns the "
                + "updated field as expanded YAML, re-validated with CedarValidator."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawField = args.get("field");
    if (rawField == null || rawField.toString().isBlank())
      return error("field is required and must not be blank");

    List<String> options = new ArrayList<>();
    Object rawOptions = args.get("options");
    if (!(rawOptions instanceof List<?> list) || list.isEmpty())
      return error("options is required and must be a non-empty array of strings — a choice "
          + "field with no options is unusable");
    for (Object option : list) {
      if (option == null || option.toString().isBlank())
        return error("options must not contain blank entries");
      options.add(option.toString());
    }

    Object rawDefault = args.get("default_option");
    String defaultOption = rawDefault == null ? null : rawDefault.toString();
    if (defaultOption != null && !options.contains(defaultOption))
      return error("default_option \"" + defaultOption + "\" is not in options " + options);

    ObjectNode fieldNode;
    try {
      fieldNode = ArtifactExchange.toObjectNode(rawField.toString());
    } catch (RuntimeException e) {
      return error("field parse failed: " + e.getMessage());
    }

    String inputType = fieldNode.path("_ui").path("inputType").asText("");
    if (!"radio".equals(inputType) && !"checkbox".equals(inputType) && !"list".equals(inputType))
      return error("options apply to choice fields only (radio-field, checkbox-field, "
          + "single-/multi-select-list-field); this field's input type is \""
          + (inputType.isEmpty() ? "unknown" : inputType) + "\". For ontology-backed values "
          + "use the set_*_constraint tools.");

    ObjectNode valueConstraints = fieldNode.withObject("_valueConstraints");
    ArrayNode literals = JACKSON.createArrayNode();
    for (String option : options) {
      ObjectNode literal = JACKSON.createObjectNode();
      literal.put("label", option);
      if (option.equals(defaultOption))
        literal.put("selectedByDefault", true);
      literals.add(literal);
    }
    valueConstraints.set("literals", literals);

    FieldSchemaArtifact field;
    try {
      field = READER.readFieldSchemaArtifact(fieldNode);
    } catch (ArtifactParseException e) {
      return error("updated field rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String validationError = ArtifactExchange.validateField(field);
    if (validationError != null)
      return error("updated field failed CedarValidator: " + validationError);

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, ArtifactExchange.exchangeYaml(field))))
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

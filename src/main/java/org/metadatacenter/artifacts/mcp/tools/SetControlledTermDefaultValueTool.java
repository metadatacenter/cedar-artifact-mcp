package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_controlled_term_default_value} — sets the schema-level default
 * value on a controlled-term field. Requires both the IRI and a human-readable label
 * because controlled-term defaults always carry both (mirrors the
 * {@code set_controlled_term_field_value} pattern on the instance side).
 *
 * <p>The schema must declare the field as controlled-term — at least one
 * class/ontology/branch/value-set constraint must already be attached. Same wire
 * collision as the rest of the controlled-term tooling: a TEXTFIELD without a
 * constraint isn't classified as ControlledTermField on JSON round-trip.
 */
public final class SetControlledTermDefaultValueTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();

  private SetControlledTermDefaultValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field_json", Map.of(
        "type", "string",
        "description",
        "CEDAR controlled-term field as YAML. Must already carry at least "
            + "one constraint (class/ontology/branch/value-set) — without one the "
            + "library doesn't classify it as a controlled-term field. JSON Schema "
            + "is also accepted."));
    properties.put("iri", Map.of(
        "type", "string",
        "description", "Default class IRI."));
    properties.put("label", Map.of(
        "type", "string",
        "description", "Human-readable label for the default class."));
    properties.put("isCompact", ArtifactExchange.isCompactSchemaProperty());

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("field_json", "iri", "label"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_controlled_term_default_value")
        .title("Set a controlled-term default value on a field")
        .description(
            "Attaches a default value (class IRI + label) to a CEDAR controlled-term "
                + "field schema. Returns the updated field as expanded YAML, "
                + "re-validated with CedarValidator.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJson = stringArg(args, "field_json");
    String iriArg = stringArg(args, "iri");
    String label = stringArg(args, "label");

    if (iriArg == null || iriArg.isBlank())
      return error("iri is required and must not be blank");
    if (label == null || label.isBlank())
      return error("label is required and must not be blank");

    URI iri;
    try {
      iri = new URI(iriArg);
    } catch (URISyntaxException e) {
      return error("iri is not a valid URI: " + e.getMessage());
    }

    // Stricter than the add_*_constraint tools: this one requires the field to already
    // be classified as ControlledTermField. A plain text-field (the wire-collision
    // case) would silently get promoted to a constraint-less controlled-term field
    // with just a default — almost certainly not what the LLM wants. Refuse and point
    // at the constraint tools.
    if (fieldJson == null || fieldJson.isBlank())
      return error("field_json is required and must not be blank");
    ObjectNode fieldObject;
    try {
      fieldObject = ArtifactExchange.toObjectNode(fieldJson);
    } catch (RuntimeException e) {
      return error("field parse failed: " + e.getMessage());
    }
    FieldSchemaArtifact field;
    try {
      field = READER.readFieldSchemaArtifact(fieldObject);
    } catch (ArtifactParseException e) {
      return error("field_json rejected by reader: " + e.getMessage());
    }
    if (!(field instanceof ControlledTermField))
      return error("field is not a controlled-term field (got "
          + field.getClass().getSimpleName() + "). Add a controlled-term constraint "
          + "to the field first via set_class_constraint / set_ontology_constraint / "
          + "set_branch_constraint / set_valueset_constraint, then set the default.");

    return ControlledTermConstraints.apply(fieldJson, builder ->
        builder.withDefaultValue(iri, label),
        ArtifactExchange.readIsCompact(args));
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

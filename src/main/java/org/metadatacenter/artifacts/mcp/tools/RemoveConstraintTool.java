package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * MCP tool {@code remove_constraint} — detaches a controlled-term constraint from a
 * field, the inverse of the {@code set_*_constraint} family. One tool rather than a
 * per-kind quartet: setting is split because the input tuples differ, but removal needs
 * only identity, and every constraint kind is identified the same way — by the IRI it
 * points at (class, ontology, branch root, or value set). The tool searches all four
 * constraint lists for that IRI.
 *
 * <p>Removing the <em>last</em> constraint is allowed but consequential: a
 * constraint-less controlled-term field is wire-indistinguishable from a plain text
 * field, so that is what the result reads back as. It is refused when it would orphan a
 * controlled-term default value — unset or change the default first (no silent drops).
 *
 * <p>Literal options are not constraints in this sense: {@code set_options} has replace
 * semantics, so removing an option is restating the list.
 */
public final class RemoveConstraintTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final List<Map.Entry<String, String>> CONSTRAINT_LISTS = List.of(
      Map.entry("class", "classes"),
      Map.entry("ontology", "ontologies"),
      Map.entry("branch", "branches"),
      Map.entry("value set", "valueSets"));

  private RemoveConstraintTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field", Map.of(
        "type", "string",
        "description",
        "CEDAR controlled-term field as YAML — one carrying at least one constraint "
            + "(class/ontology/branch/value-set). JSON Schema is also accepted."));
    properties.put("iri", Map.of(
        "type", "string",
        "description",
        "IRI of the constraint to remove — the class IRI, ontology IRI, branch root "
            + "IRI, or value-set IRI it was attached with. All constraint kinds are "
            + "searched; no kind needs to be named."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("field", "iri"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("remove_constraint")
        .title("Remove a controlled-term constraint from a field")
        .description(
            "Detaches a controlled-term constraint (class, ontology, branch, or value set) "
                + "from a CEDAR field by the IRI it points at — the inverse of the "
                + "set_*_constraint tools. Removing the last constraint leaves a plain "
                + "text-field-shaped field (an unconstrained controlled-term field is "
                + "wire-indistinguishable from one), and is refused while a controlled-term "
                + "default value would be orphaned. Returns the updated field as expanded "
                + "YAML, re-validated with CedarValidator."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldText = stringArg(args, "field");
    if (fieldText == null || fieldText.isBlank())
      return error("field is required and must not be blank");

    String iriArg = stringArg(args, "iri");
    if (iriArg == null || iriArg.isBlank())
      return error("iri is required and must not be blank");
    try {
      new URI(iriArg);
    } catch (URISyntaxException e) {
      return error("iri is not a valid URI: " + e.getMessage());
    }

    ObjectNode fieldNode;
    try {
      fieldNode = ArtifactExchange.toObjectNode(fieldText);
    } catch (RuntimeException e) {
      return error("field parse failed: " + e.getMessage());
    }

    FieldSchemaArtifact incoming;
    try {
      incoming = READER.readFieldSchemaArtifact(fieldNode);
    } catch (ArtifactParseException e) {
      return error("field rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    if (!(incoming instanceof ControlledTermField))
      return error("field carries no controlled-term constraints (an unconstrained field "
          + "is a plain text field) — nothing to remove");

    ObjectNode valueConstraints = fieldNode.withObject("_valueConstraints");

    int removed = 0;
    int remaining = 0;
    for (Map.Entry<String, String> kind : CONSTRAINT_LISTS) {
      JsonNode listNode = valueConstraints.get(kind.getValue());
      if (!(listNode instanceof ArrayNode array))
        continue;
      for (int i = array.size() - 1; i >= 0; i--) {
        if (iriArg.equals(array.get(i).path("uri").asText()))
          { array.remove(i); removed++; }
      }
      if (array.isEmpty())
        valueConstraints.remove(kind.getValue());
      else
        remaining += array.size();
    }

    if (removed == 0)
      return error("no constraint with IRI '" + iriArg + "' on this field; current "
          + "constraints:" + describeConstraints(valueConstraints));

    // No silent drops: a controlled-term default (an object with a termUri, unlike the
    // string defaults of literal fields) cannot survive on an unconstrained field.
    if (remaining == 0 && valueConstraints.path("defaultValue").isObject())
      return error("removing the last constraint would orphan the field's controlled-term "
          + "default value — unset or change the default first (set_iri_default_value), "
          + "then remove the constraint");

    FieldSchemaArtifact updated;
    try {
      updated = READER.readFieldSchemaArtifact(fieldNode);
    } catch (ArtifactParseException e) {
      return error("updated field rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String validationError = ArtifactExchange.validateField(updated);
    if (validationError != null)
      return error("updated field failed CedarValidator: " + validationError);

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, ArtifactExchange.exchangeYaml(updated))))
        .isError(false)
        .build();
  }

  /** Kind-labelled listing of the field's constraints, for one-round-trip correction. */
  private static String describeConstraints(ObjectNode valueConstraints)
  {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> kind : CONSTRAINT_LISTS) {
      JsonNode listNode = valueConstraints.get(kind.getValue());
      if (!(listNode instanceof ArrayNode array))
        continue;
      for (JsonNode entry : array) {
        String label = firstText(entry, "prefLabel", "label", "name", "acronym");
        sb.append("\n  ").append(kind.getKey()).append(": ").append(entry.path("uri").asText());
        if (!label.isEmpty())
          sb.append(" (").append(label).append(")");
      }
    }
    return sb.length() == 0 ? " (none)" : sb.toString();
  }

  private static String firstText(JsonNode entry, String... keys)
  {
    for (String key : keys) {
      String value = entry.path(key).asText("");
      if (!value.isEmpty())
        return value;
    }
    return "";
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

package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code add_ontology_constraint} — pins a controlled-term field to all
 * classes from a named ontology. The canonical input tuple ({@code ontology_iri},
 * {@code ontology_acronym}, {@code ontology_name}) matches what
 * {@code bioportal-term-mcp}'s {@code get_ontology} returns.
 */
public final class AddOntologyConstraintTool
{
  private AddOntologyConstraintTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field_json", Map.of(
        "type", "string",
        "description",
        "CEDAR controlled-term field as JSON Schema (the kind 'create_field' with "
            + "type='controlled-term-field' or 'field_from_yaml' returns)."));
    properties.put("ontology_iri", Map.of(
        "type", "string",
        "description", "Canonical IRI for the ontology."));
    properties.put("ontology_acronym", Map.of(
        "type", "string",
        "description", "Ontology acronym (e.g. 'DOID')."));
    properties.put("ontology_name", Map.of(
        "type", "string",
        "description", "Human-readable ontology name (e.g. 'Human Disease Ontology')."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("field_json", "ontology_iri", "ontology_acronym", "ontology_name"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_ontology_constraint")
        .title("Pin a controlled-term field to an ontology")
        .description(
            "Attaches an ontology-level value constraint to a CEDAR controlled-term field, "
                + "scoping its permissible values to all classes from a named ontology. "
                + "Returns the updated field JSON, re-validated with CedarValidator.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJson = stringArg(args, "field_json");
    String ontologyIri = stringArg(args, "ontology_iri");
    String ontologyAcronym = stringArg(args, "ontology_acronym");
    String ontologyName = stringArg(args, "ontology_name");

    if (isBlank(ontologyIri)) return error("ontology_iri is required and must not be blank");
    if (isBlank(ontologyAcronym)) return error("ontology_acronym is required and must not be blank");
    if (isBlank(ontologyName)) return error("ontology_name is required and must not be blank");

    URI iri;
    try {
      iri = new URI(ontologyIri);
    } catch (URISyntaxException e) {
      return error("ontology_iri is not a valid URI: " + e.getMessage());
    }

    return ControlledTermConstraints.apply(fieldJson, builder ->
        builder.withOntologyValueConstraint(iri, ontologyAcronym, ontologyName));
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static boolean isBlank(String s) { return s == null || s.isBlank(); }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

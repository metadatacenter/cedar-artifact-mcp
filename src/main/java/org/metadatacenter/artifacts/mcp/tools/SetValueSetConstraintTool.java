package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_valueset_constraint} — pins a controlled-term field to a
 * curated value set hosted in BioPortal. Value sets live in special "value-set
 * collection" ontologies (e.g. CEDARVS, HRAVS); the {@code vs_collection} argument
 * names that collection.
 */
public final class SetValueSetConstraintTool
{
  private SetValueSetConstraintTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field_json", Map.of(
        "type", "string",
        "description",
        "CEDAR controlled-term field as YAML (the kind 'create_field' with "
            + "type='controlled-term-field' or 'field_to_json' returns)."));
    properties.put("value_set_iri", Map.of(
        "type", "string",
        "description", "Canonical IRI for the value set."));
    properties.put("vs_collection", Map.of(
        "type", "string",
        "description",
        "Acronym of the value-set collection ontology (e.g. 'CEDARVS', 'HRAVS'). "
            + "Behaves like an ontology acronym in BioPortal's URL structure."));
    properties.put("name", Map.of(
        "type", "string",
        "description", "Human-readable name of the value set (skos:prefLabel)."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("field_json", "value_set_iri", "vs_collection", "name"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_valueset_constraint")
        .title("Pin a controlled-term field to a value set")
        .description(
            "Attaches a value-set constraint to a CEDAR controlled-term field, scoping "
                + "its permissible values to a curated value set hosted in BioPortal. "
                + "Returns the updated field as expanded YAML, re-validated with CedarValidator.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJson = stringArg(args, "field_json");
    String valueSetIri = stringArg(args, "value_set_iri");
    String vsCollection = stringArg(args, "vs_collection");
    String name = stringArg(args, "name");

    if (isBlank(valueSetIri)) return error("value_set_iri is required and must not be blank");
    if (isBlank(vsCollection)) return error("vs_collection is required and must not be blank");
    if (isBlank(name)) return error("name is required and must not be blank");

    URI iri;
    try {
      iri = new URI(valueSetIri);
    } catch (URISyntaxException e) {
      return error("value_set_iri is not a valid URI: " + e.getMessage());
    }

    return ControlledTermConstraints.apply(fieldJson, builder ->
        builder.withValueSetValueConstraint(iri, vsCollection, name));
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

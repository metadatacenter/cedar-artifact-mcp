package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueType;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_class_constraint} — pins a controlled-term field to a single
 * ontology class. The canonical input tuple ({@code class_iri}, {@code ontology_acronym},
 * {@code label}, {@code pref_label}) matches what a terminology MCP like
 * {@code bioportal-term-mcp}'s {@code get_class} returns.
 */
public final class SetClassConstraintTool
{
  private static final String VALUE_TYPE_CLASS = "class";
  private static final String VALUE_TYPE_VALUE = "value";

  private SetClassConstraintTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field_json", Map.of(
        "type", "string",
        "description",
        "CEDAR controlled-term field as YAML (the kind 'create_field' with "
            + "type='controlled-term-field' or 'field_to_json' returns)."));
    properties.put("class_iri", Map.of(
        "type", "string",
        "description", "Canonical IRI for the class in its ontology."));
    properties.put("ontology_acronym", Map.of(
        "type", "string",
        "description", "Acronym of the containing ontology (e.g. 'DOID', 'LOINC')."));
    properties.put("label", Map.of(
        "type", "string",
        "description", "rdfs:label for the class, as displayed by the source ontology."));
    properties.put("pref_label", Map.of(
        "type", "string",
        "description", "skos:prefLabel for the class. May equal the rdfs:label."));
    properties.put("value_type", Map.of(
        "type", "string",
        "enum", List.of(VALUE_TYPE_CLASS, VALUE_TYPE_VALUE),
        "default", VALUE_TYPE_CLASS,
        "description",
        "How the class is sourced. 'class' (default) — a class from a real ontology, "
            + "the BioPortal/get_class case. 'value' — a permissible-value entry from a "
            + "value-set or similar enumeration."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("field_json", "class_iri", "ontology_acronym", "label", "pref_label"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_class_constraint")
        .title("Pin a controlled-term field to an ontology class")
        .description(
            "Attaches a class-level value constraint to a CEDAR controlled-term field, "
                + "pinning it to a single ontology class. Returns the updated field as "
                + "expanded YAML, re-validated with CedarValidator." + ArtifactExchange.VERBATIM_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJson = stringArg(args, "field_json");
    String classIri = stringArg(args, "class_iri");
    String ontologyAcronym = stringArg(args, "ontology_acronym");
    String label = stringArg(args, "label");
    String prefLabel = stringArg(args, "pref_label");

    if (isBlank(classIri)) return error("class_iri is required and must not be blank");
    if (isBlank(ontologyAcronym)) return error("ontology_acronym is required and must not be blank");
    if (isBlank(label)) return error("label is required and must not be blank");
    if (isBlank(prefLabel)) return error("pref_label is required and must not be blank");

    String valueTypeArg = stringArgOrDefault(args, "value_type", VALUE_TYPE_CLASS);
    ValueType valueType;
    if (VALUE_TYPE_CLASS.equals(valueTypeArg)) {
      valueType = ValueType.ONTOLOGY_CLASS;
    } else if (VALUE_TYPE_VALUE.equals(valueTypeArg)) {
      valueType = ValueType.VALUE;
    } else {
      return error("value_type must be '" + VALUE_TYPE_CLASS + "' or '"
          + VALUE_TYPE_VALUE + "' (got '" + valueTypeArg + "')");
    }

    URI iri;
    try {
      iri = new URI(classIri);
    } catch (URISyntaxException e) {
      return error("class_iri is not a valid URI: " + e.getMessage());
    }

    return ControlledTermConstraints.apply(fieldJson, builder ->
        builder.withClassValueConstraint(iri, ontologyAcronym, label, prefLabel, valueType));
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static String stringArgOrDefault(Map<String, Object> args, String key, String fallback)
  {
    String value = stringArg(args, key);
    return value == null ? fallback : value;
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

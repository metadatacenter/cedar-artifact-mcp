package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_branch_constraint} — pins a controlled-term field to a subtree
 * rooted at a named class within an ontology. Use when the LLM wants "all diseases
 * under DOID:4" rather than the entire ontology or a single class.
 */
public final class SetBranchConstraintTool
{
  private SetBranchConstraintTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field_json", Map.of(
        "type", "string",
        "description",
        "CEDAR controlled-term field as YAML (the kind 'create_field' with "
            + "type='controlled-term-field' or 'field_to_json' returns)."));
    properties.put("ontology_name", Map.of(
        "type", "string",
        "description", "Human-readable ontology name (e.g. 'Human Disease Ontology')."));
    properties.put("ontology_acronym", Map.of(
        "type", "string",
        "description", "Ontology acronym (e.g. 'DOID')."));
    properties.put("branch_iri", Map.of(
        "type", "string",
        "description", "Canonical IRI for the branch's root class."));
    properties.put("branch_label", Map.of(
        "type", "string",
        "description", "Human-readable label for the branch root (e.g. 'Disease')."));
    properties.put("max_depth", Map.of(
        "type", "integer",
        "default", 0,
        "description",
        "Maximum depth from the branch root to include. Optional; defaults to 0 "
            + "(matches the library's convention for unbounded depth)."));
    properties.put("isCompact", ArtifactExchange.isCompactSchemaProperty());

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("field_json", "ontology_name", "ontology_acronym", "branch_iri", "branch_label"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_branch_constraint")
        .title("Pin a controlled-term field to an ontology branch")
        .description(
            "Attaches a branch-level value constraint to a CEDAR controlled-term field, "
                + "scoping its permissible values to a subtree rooted at the named class. "
                + "Returns the updated field as expanded YAML, re-validated with CedarValidator.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJson = stringArg(args, "field_json");
    String branchIri = stringArg(args, "branch_iri");
    String ontologyName = stringArg(args, "ontology_name");
    String ontologyAcronym = stringArg(args, "ontology_acronym");
    String branchLabel = stringArg(args, "branch_label");

    if (isBlank(branchIri)) return error("branch_iri is required and must not be blank");
    if (isBlank(ontologyName)) return error("ontology_name is required and must not be blank");
    if (isBlank(ontologyAcronym)) return error("ontology_acronym is required and must not be blank");
    if (isBlank(branchLabel)) return error("branch_label is required and must not be blank");

    int maxDepth;
    Object rawDepth = args.get("max_depth");
    if (rawDepth == null) {
      maxDepth = 0;
    } else if (rawDepth instanceof Number n) {
      maxDepth = n.intValue();
    } else {
      return error("max_depth must be an integer (got "
          + rawDepth.getClass().getSimpleName() + ")");
    }

    URI iri;
    try {
      iri = new URI(branchIri);
    } catch (URISyntaxException e) {
      return error("branch_iri is not a valid URI: " + e.getMessage());
    }

    return ControlledTermConstraints.apply(fieldJson, builder ->
        builder.withBranchValueConstraint(iri, ontologyName, ontologyAcronym, branchLabel, maxDepth),
        ArtifactExchange.readIsCompact(args));
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

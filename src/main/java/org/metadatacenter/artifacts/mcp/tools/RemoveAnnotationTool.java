package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code remove_annotation} — removes an annotation from a CEDAR artifact's root by its
 * property key. The inverse of {@code set_literal_annotation} / {@code set_iri_annotation}, one
 * tool for either value kind. Idempotent: removing an annotation that is not present succeeds and
 * returns the artifact unchanged. The artifact may be a template, element, field, or template
 * instance (kind auto-detected).
 */
public final class RemoveAnnotationTool
{
  private RemoveAnnotationTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR template, element, field, or template instance (YAML or JSON; kind "
            + "auto-detected) to remove an annotation from."));
    properties.put("annotation", Map.of(
        "type", "string",
        "description",
        "The annotation property (IRI or CURIE) to remove, e.g. skos:prefLabel. Idempotent: a "
            + "property that is not present is a no-op."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact", "annotation"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("remove_annotation")
        .title("Remove an annotation from a CEDAR artifact")
        .description(
            "Removes the annotation with the given property (IRI/CURIE) from a CEDAR template, "
                + "element, field, or template instance (kind auto-detected). The inverse of "
                + "set_literal_annotation / set_iri_annotation. Idempotent — removing an absent "
                + "annotation succeeds. Returns the updated artifact as expanded YAML."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String annotation = AnnotationEditing.stringArg(args, "annotation");
    if (annotation == null || annotation.isBlank())
      return AnnotationEditing.error("annotation is required and must not be blank");

    return AnnotationEditing.apply(AnnotationEditing.stringArg(args, "artifact"),
        annotations -> annotations.remove(annotation));
  }
}

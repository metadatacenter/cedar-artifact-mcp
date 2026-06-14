package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_iri_annotation} — attaches (or overwrites) an IRI-valued annotation on a
 * CEDAR artifact's root. An annotation is a property-IRI → value pair; this sets the value as an
 * IRI ({@code @id}). The artifact may be a template, element, field, or template instance (kind
 * auto-detected). The literal-valued counterpart is {@code set_literal_annotation};
 * {@code remove_annotation} drops one.
 */
public final class SetIriAnnotationTool
{
  private SetIriAnnotationTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR template, element, field, or template instance (YAML or JSON; kind "
            + "auto-detected) to annotate. The annotation is attached at the artifact's root."));
    properties.put("annotation", Map.of(
        "type", "string",
        "description",
        "The annotation property: an IRI or CURIE that names what is being asserted "
            + "(e.g. skos:exactMatch, http://purl.org/dc/terms/creator). Setting an annotation "
            + "whose property is already present overwrites it."));
    properties.put("iri", Map.of(
        "type", "string",
        "description",
        "The IRI value of the annotation (an absolute IRI, written as @id). For a literal "
            + "string value use set_literal_annotation instead."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact", "annotation", "iri"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_iri_annotation")
        .title("Attach an IRI annotation to a CEDAR artifact")
        .description(
            "Attaches an IRI-valued annotation to a CEDAR template, element, field, or template "
                + "instance (kind auto-detected), keyed by an annotation property IRI/CURIE; "
                + "overwrites the property if already present. Returns the updated artifact as "
                + "expanded YAML. Use set_literal_annotation for a string value, or "
                + "remove_annotation to drop one. (Element instances do not carry annotations.)"
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

    String iri = AnnotationEditing.stringArg(args, "iri");
    if (iri == null || iri.isBlank())
      return AnnotationEditing.error("iri is required and must not be blank");
    try {
      URI parsed = new URI(iri);
      if (!parsed.isAbsolute())
        return AnnotationEditing.error("iri \"" + iri + "\" must be an absolute IRI");
    } catch (URISyntaxException e) {
      return AnnotationEditing.error("iri \"" + iri + "\" is not a valid IRI: " + e.getMessage());
    }

    return AnnotationEditing.apply(AnnotationEditing.stringArg(args, "artifact"),
        annotations -> annotations.set(annotation, AnnotationEditing.iriValue(iri)));
  }
}

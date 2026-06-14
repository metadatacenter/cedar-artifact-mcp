package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_literal_annotation} — attaches (or overwrites) a literal-valued annotation
 * on a CEDAR artifact's root. An annotation is a property-IRI → value pair; this sets the value
 * as a literal string ({@code @value}). The artifact may be a template, element, field, or
 * template instance (kind auto-detected). The IRI-valued counterpart is
 * {@code set_iri_annotation}; {@code remove_annotation} drops one.
 */
public final class SetLiteralAnnotationTool
{
  private SetLiteralAnnotationTool() {}

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
            + "(e.g. skos:prefLabel, http://purl.org/dc/terms/creator). Setting an annotation "
            + "whose property is already present overwrites it."));
    properties.put("value", Map.of(
        "type", "string",
        "description",
        "The literal (string) value of the annotation. For an IRI-valued annotation use "
            + "set_iri_annotation instead."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact", "annotation", "value"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_literal_annotation")
        .title("Attach a literal annotation to a CEDAR artifact")
        .description(
            "Attaches a literal (string-valued) annotation to a CEDAR template, element, field, "
                + "or template instance (kind auto-detected), keyed by an annotation property "
                + "IRI/CURIE; overwrites the property if already present. Returns the updated "
                + "artifact as expanded YAML. Use set_iri_annotation for an IRI value, or "
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

    if (!args.containsKey("value") || args.get("value") == null)
      return AnnotationEditing.error("value is required");
    String value = args.get("value").toString();

    return AnnotationEditing.apply(AnnotationEditing.stringArg(args, "artifact"),
        annotations -> annotations.set(annotation, AnnotationEditing.literalValue(value)));
  }
}

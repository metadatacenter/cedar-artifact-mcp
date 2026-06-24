package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ParentSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.EmptyFieldInstances;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool {@code create_template_instance} — builds a template instance from a CEDAR template.
 *
 * <p>Walks the template and builds a complete instance model (an empty {@code FieldInstance}
 * per non-static, non-attribute-value child, recursing on elements). The rendered YAML, however,
 * is <em>sparse</em> — the renderer omits every unset field (no {@code value: null}, no
 * {@code {}}), so a freshly created instance is essentially just its identity (@id, name,
 * isBasedOn). The required empty slots are reconstructed from the template at the JSON boundary
 * (see {@link InstanceInflater}) by {@code validate_instance_artifact} and
 * {@code render_instance_artifact} (with {@code format: json}). The
 * LLM fills values via {@code set_literal_field_value} and friends.
 *
 * <p>The {@code isBasedOn} URI is always derived from the template's {@code @id} — the
 * instance points at exactly the template it was built from, by construction. A template
 * without an {@code @id} is rejected with guidance ({@code create_template} and
 * {@code render_schema_artifact} mint one automatically).
 */
public final class CreateTemplateInstanceTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private CreateTemplateInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "CEDAR template as YAML (the kind 'create_template' returns) to build the skeleton "
            + "instance against. JSON Schema is also accepted."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Human-readable instance name (e.g. \"Patient 42\"). Optional; defaults to the "
            + "template's own schema:name."));
    properties.put("description", Map.of(
        "type", "string",
        "description",
        "Free-text instance description. Optional. Falls back to the template's own "
            + "description when present; otherwise the instance carries no description."));
    properties.put("id", Map.of(
        "type", "string",
        "description",
        "IRI that identifies the instance itself (the @id). Optional; if omitted, a fresh "
            + "CEDAR instance IRI is auto-minted "
            + "(https://repo.metadatacenter.org/template-instances/<uuid>). Supply one only "
            + "when you have an id assigned by a CEDAR repository. Must be an absolute IRI. "
            + "Distinct from isBasedOn, which is always derived from the template's @id."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("template"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_template_instance")
        .title("Create an empty CEDAR template instance")
        .description(
            "Builds a CEDAR template instance from a template. The returned YAML is sparse — it "
                + "carries the instance identity (@id, name, isBasedOn) and only the fields that "
                + "hold a value — unset fields are omitted entirely (no null, no empty "
                + "placeholders), so a freshly created instance is essentially just its identity. "
                + "It is still structurally complete against the template — the empty fields the "
                + "JSON form requires are reconstructed at the JSON boundary, "
                + "'validate_instance_artifact' and 'render_instance_artifact' (format: json, given "
                + "the template) inflate them. "
                + "Fill values with set_literal_field_value / set_iri_field_value."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template is required and must not be blank");

    String nameOverride = stringArg(args, "name");
    String descriptionOverride = stringArg(args, "description");

    String idText = stringArg(args, "id");
    URI id;
    if (idText != null && !idText.isBlank()) {
      try {
        id = new URI(idText);
      } catch (URISyntaxException e) {
        return error("invalid id \"" + idText + "\": not a valid IRI (" + e.getMessage() + ")");
      }
      if (!id.isAbsolute())
        return error("invalid id \"" + idText + "\": an id must be an absolute IRI "
            + "(e.g. https://repo.metadatacenter.org/template-instances/5c48700a-4163-436d-8daa-95af7311cded)");
    } else {
      // No caller-supplied id — mint a top-level CEDAR instance IRI (DESIGN.md Principle 10).
      id = IdMinter.mintInstanceId();
    }

    ObjectNode templateObject;
    try {
      templateObject = ArtifactExchange.toObjectNode(templateJsonText);
    } catch (RuntimeException e) {
      return error("template parse failed: " + e.getMessage());
    }

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("template reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    if (template.jsonLdId().isEmpty())
      return error("the template carries no @id, so the instance's isBasedOn cannot be "
          + "derived. Add an id: to the template, or build it with create_template / export "
          + "it with render_schema_artifact - both mint one automatically.");
    URI isBasedOn = template.jsonLdId().get();

    String name = nameOverride == null || nameOverride.isBlank() ? template.name() : nameOverride;
    // No auto-stand-in — if the caller didn't supply a description and the template doesn't
    // carry one, default to an empty string. The JSON Schema generated from the template
    // requires schema:description to be present on the instance (the validator rejects its
    // absence), so we have to emit *something*; the YAML renderer's compact mode then
    // elides the empty value from the human-facing view. The previous "Instance of <name>"
    // placeholder made every freshly created instance look like it had been thought about.
    String description;
    if (descriptionOverride != null && !descriptionOverride.isBlank())
      description = descriptionOverride;
    else if (!template.description().isEmpty())
      description = template.description();
    else
      description = "";

    TemplateInstanceArtifact instance;
    try {
      TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder()
          .withName(name)
          .withDescription(description)
          .withIsBasedOn(isBasedOn)
          .withJsonLdId(id);
      // Mirror the template's child property-URI bindings into the instance's @context.
      // The validator requires every non-static, non-attribute-value child to appear
      // there; without this the instance fails the @context required-property check.
      for (Map.Entry<String, URI> entry : template.getChildPropertyUris().entrySet())
        builder.withJsonLdContextEntry(entry.getKey(), entry.getValue());
      populateChildren(template, builder::withSingleInstanceFieldInstance,
          builder::withMultiInstanceFieldInstances,
          builder::withSingleInstanceElementInstance,
          builder::withMultiInstanceElementInstances,
          builder::withAttributeValueFieldGroup);
      instance = builder.build();
    } catch (RuntimeException e) {
      return error("instance build failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(instance);

    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render instance as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  /**
   * Walk a parent schema and dispatch its non-static children to the five put-functions
   * on the parent instance builder. Used for both the top-level template and any nested
   * element via {@link #buildEmptyElement}.
   *
   * <p>Attribute-value fields don't live in the regular single/multi-instance maps;
   * they go through the parent's {@code attributeValueFieldInstanceGroups} as a name →
   * (inner-field-name → instance) map. For a freshly-built skeleton instance, the inner
   * map is empty — the instance carries the group placeholder, awaiting the LLM to
   * populate the per-attribute fields later.
   */
  private static void populateChildren(
      ParentSchemaArtifact parent,
      java.util.function.BiConsumer<String, FieldInstanceArtifact> putSingleField,
      java.util.function.BiConsumer<String, List<FieldInstanceArtifact>> putMultiField,
      java.util.function.BiConsumer<String, ElementInstanceArtifact> putSingleElement,
      java.util.function.BiConsumer<String, List<ElementInstanceArtifact>> putMultiElement,
      java.util.function.BiConsumer<String, LinkedHashMap<String, FieldInstanceArtifact>> putAttributeValueGroup)
  {
    for (String childKey : parent.getUi().order()) {
      if (parent.isStaticField(childKey))
        continue;  // static fields have no instance representation
      if (parent.isAttributeValueField(childKey)) {
        putAttributeValueGroup.accept(childKey, new LinkedHashMap<>());
        continue;
      }
      if (parent.isField(childKey)) {
        FieldSchemaArtifact field = parent.getFieldSchemaArtifact(childKey);
        if (field.isMultiple()) {
          putMultiField.accept(childKey, List.of());
        } else {
          putSingleField.accept(childKey, EmptyFieldInstances.emptyFor(field));
        }
      } else if (parent.isElement(childKey)) {
        ElementSchemaArtifact element = parent.getElementSchemaArtifact(childKey);
        if (element.isMultiple()) {
          putMultiElement.accept(childKey, List.of());
        } else {
          putSingleElement.accept(childKey, buildEmptyElement(element));
        }
      }
    }
  }

  /**
   * Recursively builds an empty ElementInstance matching the supplied element schema's
   * structure.
   */
  private static ElementInstanceArtifact buildEmptyElement(ElementSchemaArtifact element)
  {
    ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder();
    // See TemplateInstanceArtifact branch above — every non-static, non-attribute-value
    // child must appear in this element's @context for CedarValidator to accept it.
    for (Map.Entry<String, URI> entry : element.getChildPropertyUris().entrySet())
      builder.withJsonLdContextEntry(entry.getKey(), entry.getValue());
    populateChildren(element, builder::withSingleInstanceFieldInstance,
        builder::withMultiInstanceFieldInstances,
        builder::withSingleInstanceElementInstance,
        builder::withMultiInstanceElementInstances,
        builder::withAttributeValueFieldGroup);
    return builder.build();
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

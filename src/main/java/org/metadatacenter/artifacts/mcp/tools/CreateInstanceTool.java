package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tool {@code create_instance} — builds an empty (skeleton) template instance from
 * a CEDAR template JSON Schema.
 *
 * <p>Walks the template's schema and creates one empty {@code FieldInstance} per
 * non-static, non-attribute-value child; recurses on elements; multi-instance children
 * start as empty arrays. The result is structurally complete and ready for the LLM to
 * populate field-by-field (a future {@code set_field_value} tool, or by hand-editing
 * the YAML via {@code instance_from_yaml}).
 *
 * <p>The {@code isBasedOn} URI defaults to the template's {@code @id} when present;
 * for freshly-built templates without an {@code @id} the caller must supply
 * {@code is_based_on} explicitly.
 */
public final class CreateInstanceTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private CreateInstanceTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template JSON Schema (the kind 'template_from_yaml' or 'create_template' "
            + "returns) to build the skeleton instance against."));
    properties.put("name", Map.of(
        "type", "string",
        "description",
        "Human-readable instance name (e.g. \"Patient 42\"). Optional; defaults to the "
            + "template's own schema:name."));
    properties.put("description", Map.of(
        "type", "string",
        "description",
        "Free-text instance description. Optional; defaults to \"Instance of <template "
            + "name>\" so the result satisfies CedarValidator's required-field check."));
    properties.put("is_based_on", Map.of(
        "type", "string",
        "description",
        "URI of the template the instance is based on. Optional; defaults to the "
            + "template's @id when present. Required when the template has no @id "
            + "(e.g. a freshly-built template from 'create_template')."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("template_json"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_instance")
        .title("Create an empty CEDAR template instance")
        .description(
            "Builds an empty (skeleton) CEDAR template instance from a template JSON. "
                + "Each non-static, non-attribute-value field gets a value-less "
                + "FieldInstance; multi-instance children start as empty arrays; "
                + "elements are recursively populated. The result is structurally complete "
                + "and validates against the template; the caller fills in field values "
                + "via subsequent edits (e.g. round-trip through 'instance_to_yaml').")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template_json");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template_json is required and must not be blank");

    String nameOverride = stringArg(args, "name");
    String descriptionOverride = stringArg(args, "description");
    String isBasedOnOverride = stringArg(args, "is_based_on");

    JsonNode parsed;
    try {
      parsed = JACKSON2.readTree(templateJsonText);
    } catch (Exception e) {
      return error("template_json parse failed: " + e.getMessage());
    }
    if (!(parsed instanceof ObjectNode templateObject))
      return error("template_json must parse to a JSON object");

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template_json rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("template reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    URI isBasedOn;
    if (isBasedOnOverride != null && !isBasedOnOverride.isBlank()) {
      try {
        isBasedOn = new URI(isBasedOnOverride);
      } catch (URISyntaxException e) {
        return error("is_based_on is not a valid URI: " + e.getMessage());
      }
    } else if (template.jsonLdId().isPresent()) {
      isBasedOn = template.jsonLdId().get();
    } else {
      return error("template has no @id; supply is_based_on explicitly "
          + "(a freshly-built template from create_template has no @id until saved)");
    }

    String name = nameOverride == null || nameOverride.isBlank() ? template.name() : nameOverride;
    String description;
    if (descriptionOverride != null && !descriptionOverride.isBlank()) {
      description = descriptionOverride;
    } else if (!template.description().isEmpty()) {
      description = template.description();
    } else {
      // The instance renderer only emits schema:description when description.isPresent();
      // the template requires it. Default to a non-empty stand-in so validate_instance
      // passes the structural check without forcing the LLM to think about it.
      description = "Instance of " + template.name();
    }

    TemplateInstanceArtifact instance;
    try {
      TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder()
          .withName(name)
          .withDescription(description)
          .withIsBasedOn(isBasedOn);
      // Mirror the template's child property-URI bindings into the instance's @context.
      // The validator requires every non-static, non-attribute-value child to appear
      // there; without this the instance fails the @context required-property check.
      for (Map.Entry<String, URI> entry : template.getChildPropertyUris().entrySet())
        builder.withJsonLdContextEntry(entry.getKey(), entry.getValue());
      populateChildren(template, builder::withSingleInstanceFieldInstance,
          builder::withMultiInstanceFieldInstances,
          builder::withSingleInstanceElementInstance,
          builder::withMultiInstanceElementInstances);
      instance = builder.build();
    } catch (RuntimeException e) {
      return error("instance build failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(instance);

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered instance: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  /**
   * Walk a parent schema and dispatch its non-static, non-attribute-value children to
   * the four put-functions on the parent instance builder. Used for both the top-level
   * template and any nested element via {@link #buildEmptyElement}.
   */
  private static void populateChildren(
      ParentSchemaArtifact parent,
      java.util.function.BiConsumer<String, FieldInstanceArtifact> putSingleField,
      java.util.function.BiConsumer<String, List<FieldInstanceArtifact>> putMultiField,
      java.util.function.BiConsumer<String, ElementInstanceArtifact> putSingleElement,
      java.util.function.BiConsumer<String, List<ElementInstanceArtifact>> putMultiElement)
  {
    for (String childKey : parent.getNonStaticNonAttributeValueChildKeys()) {
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
        builder::withMultiInstanceElementInstances);
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

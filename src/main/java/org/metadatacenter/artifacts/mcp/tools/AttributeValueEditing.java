package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Shared machinery for the attribute-value tools ({@code set_attribute_value},
 * {@code unset_attribute_value}). An attribute-value field holds a group of dynamic
 * name→value entries entered at fill time; each value is a literal ({@code @value}) — the CEDAR
 * JSON Schema an attribute-value field generates for its entries lists {@code @value} as the only
 * required member and forbids {@code @id}, so there is no IRI form.
 *
 * <p>This reads the template + instance, inflates the instance against the template (so the
 * attribute-value group exists), confirms {@code field_path} resolves to an attribute-value field,
 * rewrites that field's group via {@link InstanceAttributeValues}, and renders the updated
 * instance as expanded YAML.
 */
final class AttributeValueEditing
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private AttributeValueEditing() {}

  /** A literal attribute-value entry: {@code {"@value": value}}, no type or label. */
  static FieldInstanceArtifact literal(String value)
  {
    return FieldInstanceArtifact.create(List.of(), Optional.empty(), Optional.of(value),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  static McpSchema.CallToolResult apply(
      String templateText, String instanceText, String fieldPath,
      UnaryOperator<LinkedHashMap<String, FieldInstanceArtifact>> groupOp)
  {
    if (templateText == null || templateText.isBlank())
      return error("template is required and must not be blank");
    if (instanceText == null || instanceText.isBlank())
      return error("instance is required and must not be blank");
    if (fieldPath == null || fieldPath.isBlank())
      return error("field_path is required and must not be blank");

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(ArtifactExchange.toObjectNode(templateText));
    } catch (ArtifactParseException e) {
      return error("template rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("template parse failed: " + e.getMessage());
    }

    TemplateInstanceArtifact instance;
    try {
      instance = READER.readTemplateInstanceArtifact(ArtifactExchange.toObjectNode(instanceText));
    } catch (ArtifactParseException e) {
      return error("instance rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("instance parse failed: " + e.getMessage());
    }

    // Inflate so the attribute-value group (and any enclosing elements) exist before editing.
    try {
      instance = InstanceInflater.inflate(template, instance);
    } catch (RuntimeException e) {
      return error("instance does not match template (could not inflate): " + e.getMessage());
    }

    FieldSchemaArtifact schemaField;
    try {
      schemaField = SchemaPaths.resolveFieldIgnoringLeafCardinality(template, fieldPath);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }
    if (!schemaField.isAttributeValue())
      return error("field at '" + fieldPath + "' is not an attribute-value field (it is "
          + schemaField.fieldUi().inputType() + ") — use set_literal_field_value / set_iri_field_value "
          + "for a regular field");

    TemplateInstanceArtifact updated;
    try {
      updated = InstanceAttributeValues.apply(instance, fieldPath, groupOp);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("attribute-value update failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    String yaml;
    try {
      ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(updated);
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated instance as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ParentInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ParentSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reconstructs a complete template instance from a (possibly sparse) instance and its template.
 *
 * <p>YAML instances are sparse: a field with no value is omitted entirely (no {@code value: null},
 * no {@code {}}). But the canonical CEDAR <em>JSON</em> instance must mirror the template — every
 * non-static, non-attribute-value field present, even when empty, because the template's JSON
 * Schema marks those properties required. That "all fields present" rule is a JSON-serialization
 * concern, not a property of the model or the YAML. This class bridges the two: it re-adds the
 * missing empty slots (recursing into elements) while preserving every value the sparse instance
 * already carries, so validation and {@code *_to_json} export operate on a complete instance.
 *
 * <p>Inflation is keyed off the template structure and only fills slots that are <em>absent</em>;
 * present values (and any extra keys, e.g. populated attribute-value groups) are kept untouched.
 */
final class InstanceInflater
{
  private InstanceInflater() {}

  /** Inflate a (possibly sparse) template instance to a complete one against its template. */
  static TemplateInstanceArtifact inflate(TemplateSchemaArtifact template, TemplateInstanceArtifact sparse)
  {
    TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder(sparse);
    ensureContext(template.getChildPropertyUris(), sparse.jsonLdContext(), builder::withJsonLdContextEntry);
    fillParent(template, sparse,
        builder::withSingleInstanceFieldInstance, builder::withMultiInstanceFieldInstances,
        builder::withSingleInstanceElementInstance, builder::withMultiInstanceElementInstances,
        builder::withAttributeValueFieldGroup,
        builder::withoutSingleInstanceElementInstance, builder::withoutMultiInstanceElementInstances);
    return builder.build();
  }

  /** Inflate a (possibly sparse) element instance to a complete one against its element schema. */
  static ElementInstanceArtifact inflateElement(ElementSchemaArtifact schema, ElementInstanceArtifact sparse)
  {
    ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder(sparse);
    ensureContext(schema.getChildPropertyUris(), sparse.jsonLdContext(), builder::withJsonLdContextEntry);
    fillParent(schema, sparse,
        builder::withSingleInstanceFieldInstance, builder::withMultiInstanceFieldInstances,
        builder::withSingleInstanceElementInstance, builder::withMultiInstanceElementInstances,
        builder::withAttributeValueFieldGroup,
        builder::withoutSingleInstanceElementInstance, builder::withoutMultiInstanceElementInstances);
    return builder.build();
  }

  /** An all-empty element instance matching the element schema — every regular child present, value-less. */
  static ElementInstanceArtifact emptyElement(ElementSchemaArtifact schema)
  {
    ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder();
    for (Map.Entry<String, URI> entry : schema.getChildPropertyUris().entrySet())
      builder.withJsonLdContextEntry(entry.getKey(), entry.getValue());
    fillParent(schema, null,
        builder::withSingleInstanceFieldInstance, builder::withMultiInstanceFieldInstances,
        builder::withSingleInstanceElementInstance, builder::withMultiInstanceElementInstances,
        builder::withAttributeValueFieldGroup,
        builder::withoutSingleInstanceElementInstance, builder::withoutMultiInstanceElementInstances);
    return builder.build();
  }

  private static void ensureContext(Map<String, URI> required, Map<String, URI> existing,
      BiConsumer<String, URI> put)
  {
    for (Map.Entry<String, URI> entry : required.entrySet())
      if (!existing.containsKey(entry.getKey()))
        put.accept(entry.getKey(), entry.getValue());
  }

  /**
   * Walk the schema's children and, for each non-static child the instance is missing, add the
   * empty slot the JSON form requires; recurse into present elements (which are themselves
   * sparse). {@code existing} is null when building a fresh empty element — then every child is
   * added empty.
   */
  private static void fillParent(
      ParentSchemaArtifact schema, ParentInstanceArtifact existing,
      BiConsumer<String, FieldInstanceArtifact> putSingleField,
      BiConsumer<String, List<FieldInstanceArtifact>> putMultiField,
      BiConsumer<String, ElementInstanceArtifact> putSingleElement,
      BiConsumer<String, List<ElementInstanceArtifact>> putMultiElement,
      BiConsumer<String, LinkedHashMap<String, FieldInstanceArtifact>> putAttrGroup,
      Consumer<String> removeSingleElement, Consumer<String> removeMultiElement)
  {
    for (String childKey : schema.getUi().order()) {
      if (schema.isStaticField(childKey))
        continue;

      if (schema.isAttributeValueField(childKey)) {
        if (existing == null || !existing.attributeValueFieldInstanceGroups().containsKey(childKey))
          putAttrGroup.accept(childKey, new LinkedHashMap<>());
        continue;
      }

      if (schema.isField(childKey)) {
        FieldSchemaArtifact field = schema.getFieldSchemaArtifact(childKey);
        if (field.isMultiple()) {
          if (existing == null || !existing.multiInstanceFieldInstances().containsKey(childKey))
            putMultiField.accept(childKey, List.of());
        } else {
          if (existing == null || !existing.singleInstanceFieldInstances().containsKey(childKey))
            putSingleField.accept(childKey, EmptyFieldInstances.emptyFor(field));
        }
      } else if (schema.isElement(childKey)) {
        ElementSchemaArtifact element = schema.getElementSchemaArtifact(childKey);
        if (element.isMultiple()) {
          if (existing != null && existing.multiInstanceElementInstances().containsKey(childKey)) {
            List<ElementInstanceArtifact> inflated = new ArrayList<>();
            for (ElementInstanceArtifact e : existing.multiInstanceElementInstances().get(childKey))
              inflated.add(inflateElement(element, e));
            removeMultiElement.accept(childKey);
            putMultiElement.accept(childKey, inflated);
          } else {
            putMultiElement.accept(childKey, List.of());
          }
        } else {
          if (existing != null && existing.singleInstanceElementInstances().containsKey(childKey)) {
            ElementInstanceArtifact inflated =
                inflateElement(element, existing.singleInstanceElementInstances().get(childKey));
            removeSingleElement.accept(childKey);
            putSingleElement.accept(childKey, inflated);
          } else {
            putSingleElement.accept(childKey, emptyElement(element));
          }
        }
      }
    }
  }
}

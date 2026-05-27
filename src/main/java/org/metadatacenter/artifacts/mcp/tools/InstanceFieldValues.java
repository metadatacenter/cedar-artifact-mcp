package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Walks a CEDAR template instance to a {@code field_path}, transforms the leaf
 * {@link FieldInstanceArtifact}, and returns a new instance with the result
 * substituted in place.
 *
 * <p>Path segments use the same shape as {@link SchemaPaths}: {@code <key>} for
 * single-instance children, {@code <key>[<index>]} for multi-instance children.
 * Intermediate multi-instance element indices must point at an existing element
 * (no auto-creation); leaf multi-instance field indices may equal the current
 * list size to append (extend the list by one), but must not exceed it.
 *
 * <p>Artifacts are immutable; each level on the way down gets rebuilt via its
 * {@code builder(existing)} constructor with the matching child removed and re-added.
 */
final class InstanceFieldValues
{
  private InstanceFieldValues() {}

  /**
   * @throws IllegalArgumentException if the path is malformed, an intermediate step
   *   doesn't resolve to an element, a leaf isn't a field, or a multi-instance index
   *   is out of range.
   */
  static TemplateInstanceArtifact apply(
      TemplateInstanceArtifact instance,
      String path,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    List<SchemaPaths.Segment> segments = SchemaPaths.parse(path);
    return updateTemplate(instance, segments, transform);
  }

  private static TemplateInstanceArtifact updateTemplate(
      TemplateInstanceArtifact instance,
      List<SchemaPaths.Segment> path,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      if (head.hasIndex()) {
        // Leaf multi-instance field
        List<FieldInstanceArtifact> currentList =
            instance.multiInstanceFieldInstances().get(head.key());
        if (currentList == null)
          throw new IllegalArgumentException(
              "no multi-instance field at '" + head.key() + "' on the instance");
        List<FieldInstanceArtifact> updatedList = applyToMultiInstanceField(
            currentList, head.key(), head.index(), transform);
        return TemplateInstanceArtifact.builder(instance)
            .withoutMultiInstanceFieldInstances(head.key())
            .withMultiInstanceFieldInstances(head.key(), updatedList)
            .build();
      }
      // Leaf single-instance field
      FieldInstanceArtifact current = instance.singleInstanceFieldInstances().get(head.key());
      if (current == null)
        throw new IllegalArgumentException(
            "no single-instance field at '" + head.key() + "' on the instance");
      FieldInstanceArtifact updated = transform.apply(current);
      return TemplateInstanceArtifact.builder(instance)
          .withoutSingleInstanceFieldInstance(head.key())
          .withSingleInstanceFieldInstance(head.key(), updated)
          .build();
    }

    // Intermediate element step
    List<SchemaPaths.Segment> rest = path.subList(1, path.size());
    if (head.hasIndex()) {
      List<ElementInstanceArtifact> currentList =
          instance.multiInstanceElementInstances().get(head.key());
      if (currentList == null)
        throw new IllegalArgumentException(
            "no multi-instance element at '" + head.key() + "' on the instance");
      List<ElementInstanceArtifact> updatedList = applyToMultiInstanceElement(
          currentList, head.key(), head.index(), rest, transform);
      return TemplateInstanceArtifact.builder(instance)
          .withoutMultiInstanceElementInstances(head.key())
          .withMultiInstanceElementInstances(head.key(), updatedList)
          .build();
    }
    ElementInstanceArtifact childElement = instance.singleInstanceElementInstances().get(head.key());
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head.key() + "' on the instance");
    ElementInstanceArtifact updatedChild = updateElement(childElement, rest, transform);
    return TemplateInstanceArtifact.builder(instance)
        .withoutSingleInstanceElementInstance(head.key())
        .withSingleInstanceElementInstance(head.key(), updatedChild)
        .build();
  }

  private static ElementInstanceArtifact updateElement(
      ElementInstanceArtifact element,
      List<SchemaPaths.Segment> path,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      if (head.hasIndex()) {
        List<FieldInstanceArtifact> currentList =
            element.multiInstanceFieldInstances().get(head.key());
        if (currentList == null)
          throw new IllegalArgumentException(
              "no multi-instance field at '" + head.key() + "' on element");
        List<FieldInstanceArtifact> updatedList = applyToMultiInstanceField(
            currentList, head.key(), head.index(), transform);
        return ElementInstanceArtifact.builder(element)
            .withoutMultiInstanceFieldInstances(head.key())
            .withMultiInstanceFieldInstances(head.key(), updatedList)
            .build();
      }
      FieldInstanceArtifact current = element.singleInstanceFieldInstances().get(head.key());
      if (current == null)
        throw new IllegalArgumentException(
            "no single-instance field at '" + head.key() + "' on element");
      FieldInstanceArtifact updated = transform.apply(current);
      return ElementInstanceArtifact.builder(element)
          .withoutSingleInstanceFieldInstance(head.key())
          .withSingleInstanceFieldInstance(head.key(), updated)
          .build();
    }

    List<SchemaPaths.Segment> rest = path.subList(1, path.size());
    if (head.hasIndex()) {
      List<ElementInstanceArtifact> currentList =
          element.multiInstanceElementInstances().get(head.key());
      if (currentList == null)
        throw new IllegalArgumentException(
            "no multi-instance element at '" + head.key() + "' on element");
      List<ElementInstanceArtifact> updatedList = applyToMultiInstanceElement(
          currentList, head.key(), head.index(), rest, transform);
      return ElementInstanceArtifact.builder(element)
          .withoutMultiInstanceElementInstances(head.key())
          .withMultiInstanceElementInstances(head.key(), updatedList)
          .build();
    }
    ElementInstanceArtifact childElement = element.singleInstanceElementInstances().get(head.key());
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head.key() + "' on element");
    ElementInstanceArtifact updatedChild = updateElement(childElement, rest, transform);
    return ElementInstanceArtifact.builder(element)
        .withoutSingleInstanceElementInstance(head.key())
        .withSingleInstanceElementInstance(head.key(), updatedChild)
        .build();
  }

  /**
   * Replace the value at {@code index} in a multi-instance field's list. An index
   * equal to the current list size appends a new value; any larger index errors.
   */
  private static List<FieldInstanceArtifact> applyToMultiInstanceField(
      List<FieldInstanceArtifact> currentList, String key, int index,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    if (index < 0 || index > currentList.size())
      throw new IllegalArgumentException(
          "multi-instance field '" + key + "' index " + index + " out of range "
              + "(list has " + currentList.size() + " entries; use index <= size to append)");
    List<FieldInstanceArtifact> updated = new ArrayList<>(currentList);
    FieldInstanceArtifact replacement = transform.apply(
        index < currentList.size() ? currentList.get(index) : null);
    if (index == currentList.size())
      updated.add(replacement);
    else
      updated.set(index, replacement);
    return updated;
  }

  /**
   * Walk into a multi-instance element at {@code index}, apply the rest of the path
   * to that element, and rebuild the list with the updated element substituted at
   * {@code index}. Unlike the field case, this does not append — intermediate
   * elements must exist already.
   */
  private static List<ElementInstanceArtifact> applyToMultiInstanceElement(
      List<ElementInstanceArtifact> currentList, String key, int index,
      List<SchemaPaths.Segment> rest,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    if (index < 0 || index >= currentList.size())
      throw new IllegalArgumentException(
          "multi-instance element '" + key + "' index " + index + " out of range "
              + "(list has " + currentList.size() + " entries)");
    ElementInstanceArtifact updatedAt = updateElement(currentList.get(index), rest, transform);
    List<ElementInstanceArtifact> updated = new ArrayList<>(currentList);
    updated.set(index, updatedAt);
    return updated;
  }
}

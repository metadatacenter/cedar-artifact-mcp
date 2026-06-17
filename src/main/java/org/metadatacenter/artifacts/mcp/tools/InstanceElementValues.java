package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ParentSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Walks a CEDAR template instance to a {@code field_path} whose leaf names an
 * <em>element</em> child and sets a whole element instance there — the element counterpart of
 * {@link InstanceFieldValues}, which sets leaf field values.
 *
 * <p>Leaf semantics, decided against the schema:
 * <ul>
 *   <li>single-instance element, no index — replace the element instance;</li>
 *   <li>multi-instance element with {@code [N]} — N &lt; size replaces entry N, N == size
 *       appends (the same append rule as multi-instance leaf fields), N &gt; size errors;</li>
 *   <li>multi-instance element without an index — an error; entries are positional, so the
 *       call must say where the element instance goes.</li>
 * </ul>
 *
 * <p>Intermediate segments follow the same rules as the other instance walkers: element
 * steps, multi-instance ones indexed, and they must already exist.
 */
final class InstanceElementValues
{
  private InstanceElementValues() {}

  /**
   * @throws IllegalArgumentException if the path is malformed or doesn't resolve against
   *   the schema, the leaf isn't an element child, or an index is out of range.
   */
  static TemplateInstanceArtifact set(
      TemplateSchemaArtifact template, TemplateInstanceArtifact instance, String path,
      ElementInstanceArtifact entry)
  {
    List<SchemaPaths.Segment> segments = SchemaPaths.parse(path);
    ElementSchemaArtifact leafSchema = classifyLeaf(template, segments, path);
    // Inflate the incoming element instance against its schema so every child slot exists. This is
    // what keeps the entry recognizable as an element at the serialization boundaries (the
    // JSON form derives its @context from the children) and immediately addressable by the
    // set_*_field_value walkers.
    ElementInstanceArtifact inflated = InstanceInflater.inflateElement(leafSchema, entry);
    return setOnTemplate(instance, segments, inflated);
  }

  /** Validate the path against the schema; the leaf must name an element child. */
  private static ElementSchemaArtifact classifyLeaf(
      TemplateSchemaArtifact template, List<SchemaPaths.Segment> segments, String path)
  {
    ParentSchemaArtifact current = template;
    for (int i = 0; i < segments.size() - 1; i++) {
      SchemaPaths.Segment seg = segments.get(i);
      if (!current.isElement(seg.key()))
        throw new IllegalArgumentException("no element child '" + seg.key()
            + "' on schema (looking up field_path '" + path + "')");
      ElementSchemaArtifact next = current.getElementSchemaArtifact(seg.key());
      if (next.isMultiple() && !seg.hasIndex())
        throw new IllegalArgumentException("multi-instance element '" + seg.key()
            + "' requires an index on intermediate steps (e.g. '" + seg.key() + "[0]')");
      if (!next.isMultiple() && seg.hasIndex())
        throw new IllegalArgumentException(
            "single-instance element '" + seg.key() + "' must not carry an index");
      current = next;
    }

    SchemaPaths.Segment leaf = segments.get(segments.size() - 1);
    if (!current.isElement(leaf.key()))
      throw new IllegalArgumentException("'" + leaf.key() + "' is not an element child of "
          + "the schema — set_element_instance places whole element instances; for field values "
          + "use the set_*_field_value tools (looking up field_path '" + path + "')");
    ElementSchemaArtifact leafSchema = current.getElementSchemaArtifact(leaf.key());
    if (leafSchema.isMultiple() && !leaf.hasIndex())
      throw new IllegalArgumentException("multi-instance element '" + leaf.key()
          + "' requires an index: '" + leaf.key() + "[N]' replaces entry N, and N equal "
          + "to the current list size appends");
    if (!leafSchema.isMultiple() && leaf.hasIndex())
      throw new IllegalArgumentException(
          "single-instance element '" + leaf.key() + "' must not carry an index");
    return leafSchema;
  }

  private static TemplateInstanceArtifact setOnTemplate(
      TemplateInstanceArtifact instance, List<SchemaPaths.Segment> path,
      ElementInstanceArtifact entry)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder(instance);
      applyLeaf(head, entry,
          instance.singleInstanceElementInstances(),
          instance.multiInstanceElementInstances(),
          builder::replaceSingleInstanceElementInstance,
          builder::replaceMultiInstanceElementInstances);
      return builder.build();
    }

    List<SchemaPaths.Segment> rest = path.subList(1, path.size());
    if (head.hasIndex()) {
      List<ElementInstanceArtifact> currentList =
          instance.multiInstanceElementInstances().get(head.key());
      if (currentList == null)
        throw new IllegalArgumentException(
            "no multi-instance element at '" + head.key() + "' on the instance");
      return TemplateInstanceArtifact.builder(instance)
          .replaceMultiInstanceElementInstances(head.key(),
              descendMultiElement(currentList, head, rest, entry))
          .build();
    }
    ElementInstanceArtifact childElement = instance.singleInstanceElementInstances().get(head.key());
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head.key() + "' on the instance");
    return TemplateInstanceArtifact.builder(instance)
        .replaceSingleInstanceElementInstance(head.key(), setOnElement(childElement, rest, entry))
        .build();
  }

  private static ElementInstanceArtifact setOnElement(
      ElementInstanceArtifact element, List<SchemaPaths.Segment> path,
      ElementInstanceArtifact entry)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder(element);
      applyLeaf(head, entry,
          element.singleInstanceElementInstances(),
          element.multiInstanceElementInstances(),
          builder::replaceSingleInstanceElementInstance,
          builder::replaceMultiInstanceElementInstances);
      return builder.build();
    }

    List<SchemaPaths.Segment> rest = path.subList(1, path.size());
    if (head.hasIndex()) {
      List<ElementInstanceArtifact> currentList =
          element.multiInstanceElementInstances().get(head.key());
      if (currentList == null)
        throw new IllegalArgumentException(
            "no multi-instance element at '" + head.key() + "' on element");
      return ElementInstanceArtifact.builder(element)
          .replaceMultiInstanceElementInstances(head.key(),
              descendMultiElement(currentList, head, rest, entry))
          .build();
    }
    ElementInstanceArtifact childElement = element.singleInstanceElementInstances().get(head.key());
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head.key() + "' on element");
    return ElementInstanceArtifact.builder(element)
        .replaceSingleInstanceElementInstance(head.key(), setOnElement(childElement, rest, entry))
        .build();
  }

  /** Walk into a multi-instance element at the head's index and recurse with the rest. */
  private static List<ElementInstanceArtifact> descendMultiElement(
      List<ElementInstanceArtifact> currentList, SchemaPaths.Segment head,
      List<SchemaPaths.Segment> rest, ElementInstanceArtifact entry)
  {
    if (head.index() < 0 || head.index() >= currentList.size())
      throw new IllegalArgumentException(
          "multi-instance element '" + head.key() + "' index " + head.index()
              + " out of range (list has " + currentList.size() + " entries)");
    List<ElementInstanceArtifact> updated = new ArrayList<>(currentList);
    updated.set(head.index(), setOnElement(currentList.get(head.index()), rest, entry));
    return updated;
  }

  private static void applyLeaf(
      SchemaPaths.Segment leaf, ElementInstanceArtifact entry,
      Map<String, ElementInstanceArtifact> singleElements,
      Map<String, List<ElementInstanceArtifact>> multiElements,
      BiConsumer<String, ElementInstanceArtifact> replaceSingle,
      BiConsumer<String, List<ElementInstanceArtifact>> replaceMulti)
  {
    if (leaf.hasIndex()) {
      List<ElementInstanceArtifact> currentList = multiElements.get(leaf.key());
      if (currentList == null)
        throw new IllegalArgumentException(
            "no multi-instance element at '" + leaf.key() + "' on the instance");
      if (leaf.index() < 0 || leaf.index() > currentList.size())
        throw new IllegalArgumentException(
            "multi-instance element '" + leaf.key() + "' index " + leaf.index()
                + " out of range (list has " + currentList.size()
                + " entries; use index <= size to append)");
      List<ElementInstanceArtifact> updated = new ArrayList<>(currentList);
      if (leaf.index() == currentList.size())
        updated.add(entry);
      else
        updated.set(leaf.index(), entry);
      replaceMulti.accept(leaf.key(), updated);
      return;
    }
    if (!singleElements.containsKey(leaf.key()))
      throw new IllegalArgumentException(
          "no single-instance element at '" + leaf.key() + "' on the instance");
    replaceSingle.accept(leaf.key(), entry);
  }
}

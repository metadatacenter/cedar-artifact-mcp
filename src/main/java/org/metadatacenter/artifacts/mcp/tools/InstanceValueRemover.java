package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ParentSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Walks a CEDAR template instance to a {@code field_path} and removes the value(s)
 * the leaf segment addresses. The inverse of {@link InstanceFieldValues}: where that
 * helper substitutes a new leaf value, this one clears or deletes.
 *
 * <p>What the leaf means is decided against the <em>schema</em>, not the instance:
 * <ul>
 *   <li>single-instance field, no index — clear the value back to unset (replaced with
 *       the matching empty instance, which the sparse YAML rendering then omits);</li>
 *   <li>multi-instance field with {@code [N]} — delete entry N (the list shrinks and
 *       later indices shift down);</li>
 *   <li>multi-instance field without an index — clear the whole list;</li>
 *   <li>multi-instance element with {@code [N]} — delete that sub-record entirely;</li>
 *   <li>multi-instance element without an index — clear the whole list.</li>
 * </ul>
 *
 * <p>Clearing is idempotent (clearing an already-empty slot succeeds); deleting at an
 * out-of-range index is an error — unlike the setters, where index == size appends,
 * there is nothing coherent to delete there. Intermediate segments follow the same
 * rules as {@link InstanceFieldValues}: element steps, multi-instance ones indexed,
 * and they must already exist.
 */
final class InstanceValueRemover
{
  private InstanceValueRemover() {}

  /**
   * @throws IllegalArgumentException if the path is malformed or doesn't resolve against
   *   the schema, a delete index is out of range, or the leaf is a single-instance
   *   element, a static field, or an attribute-value field.
   */
  static TemplateInstanceArtifact remove(
      TemplateSchemaArtifact template, TemplateInstanceArtifact instance, String path)
  {
    List<SchemaPaths.Segment> segments = SchemaPaths.parse(path);
    LeafOp op = classifyLeaf(template, segments, path);
    return removeFromTemplate(instance, segments, op);
  }

  private enum Kind
  {
    CLEAR_SINGLE_FIELD, DELETE_MULTI_FIELD_ENTRY, CLEAR_MULTI_FIELD,
    DELETE_MULTI_ELEMENT_ENTRY, CLEAR_MULTI_ELEMENT
  }

  /** What the leaf segment means, decided against the schema. */
  private record LeafOp(Kind kind, FieldSchemaArtifact fieldSchema) {}

  private static LeafOp classifyLeaf(
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
    if (current.isStaticField(leaf.key()))
      throw new IllegalArgumentException(
          "'" + leaf.key() + "' is a static field — it holds no instance value to unset");
    if (current.isAttributeValueField(leaf.key()))
      throw new IllegalArgumentException(
          "'" + leaf.key() + "' is an attribute-value field; its group is not addressable here");
    if (current.isField(leaf.key())) {
      FieldSchemaArtifact field = current.getFieldSchemaArtifact(leaf.key());
      if (field.isMultiple())
        return new LeafOp(
            leaf.hasIndex() ? Kind.DELETE_MULTI_FIELD_ENTRY : Kind.CLEAR_MULTI_FIELD, field);
      if (leaf.hasIndex())
        throw new IllegalArgumentException(
            "single-instance field '" + leaf.key() + "' must not carry an index");
      return new LeafOp(Kind.CLEAR_SINGLE_FIELD, field);
    }
    if (current.isElement(leaf.key())) {
      ElementSchemaArtifact element = current.getElementSchemaArtifact(leaf.key());
      if (!element.isMultiple())
        throw new IllegalArgumentException("'" + leaf.key() + "' is a single-instance "
            + "element — unset its fields individually (e.g. '" + leaf.key() + "/<field>')");
      return new LeafOp(
          leaf.hasIndex() ? Kind.DELETE_MULTI_ELEMENT_ENTRY : Kind.CLEAR_MULTI_ELEMENT, null);
    }
    throw new IllegalArgumentException(
        "no child '" + leaf.key() + "' on schema (looking up field_path '" + path + "')");
  }

  private static TemplateInstanceArtifact removeFromTemplate(
      TemplateInstanceArtifact instance, List<SchemaPaths.Segment> path, LeafOp op)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder(instance);
      applyLeaf(head, op,
          instance.singleInstanceFieldInstances(),
          instance.multiInstanceFieldInstances(),
          instance.multiInstanceElementInstances(),
          builder::replaceSingleInstanceFieldInstance,
          builder::replaceMultiInstanceFieldInstances,
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
              descendMultiElement(currentList, head, rest, op))
          .build();
    }
    ElementInstanceArtifact childElement = instance.singleInstanceElementInstances().get(head.key());
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head.key() + "' on the instance");
    return TemplateInstanceArtifact.builder(instance)
        .replaceSingleInstanceElementInstance(head.key(), removeFromElement(childElement, rest, op))
        .build();
  }

  private static ElementInstanceArtifact removeFromElement(
      ElementInstanceArtifact element, List<SchemaPaths.Segment> path, LeafOp op)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder(element);
      applyLeaf(head, op,
          element.singleInstanceFieldInstances(),
          element.multiInstanceFieldInstances(),
          element.multiInstanceElementInstances(),
          builder::replaceSingleInstanceFieldInstance,
          builder::replaceMultiInstanceFieldInstances,
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
              descendMultiElement(currentList, head, rest, op))
          .build();
    }
    ElementInstanceArtifact childElement = element.singleInstanceElementInstances().get(head.key());
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head.key() + "' on element");
    return ElementInstanceArtifact.builder(element)
        .replaceSingleInstanceElementInstance(head.key(), removeFromElement(childElement, rest, op))
        .build();
  }

  /** Walk into a multi-instance element at the head's index and recurse with the rest. */
  private static List<ElementInstanceArtifact> descendMultiElement(
      List<ElementInstanceArtifact> currentList, SchemaPaths.Segment head,
      List<SchemaPaths.Segment> rest, LeafOp op)
  {
    if (head.index() < 0 || head.index() >= currentList.size())
      throw new IllegalArgumentException(
          "multi-instance element '" + head.key() + "' index " + head.index()
              + " out of range (list has " + currentList.size() + " entries)");
    List<ElementInstanceArtifact> updated = new ArrayList<>(currentList);
    updated.set(head.index(), removeFromElement(currentList.get(head.index()), rest, op));
    return updated;
  }

  private static void applyLeaf(
      SchemaPaths.Segment leaf, LeafOp op,
      Map<String, FieldInstanceArtifact> singleFields,
      Map<String, List<FieldInstanceArtifact>> multiFields,
      Map<String, List<ElementInstanceArtifact>> multiElements,
      BiConsumer<String, FieldInstanceArtifact> replaceSingleField,
      BiConsumer<String, List<FieldInstanceArtifact>> replaceMultiField,
      BiConsumer<String, List<ElementInstanceArtifact>> replaceMultiElements)
  {
    switch (op.kind()) {
      case CLEAR_SINGLE_FIELD -> {
        if (!singleFields.containsKey(leaf.key()))
          throw new IllegalArgumentException(
              "no single-instance field at '" + leaf.key() + "' on the instance");
        replaceSingleField.accept(leaf.key(), EmptyFieldInstances.emptyFor(op.fieldSchema()));
      }
      case DELETE_MULTI_FIELD_ENTRY -> {
        List<FieldInstanceArtifact> currentList = multiFields.get(leaf.key());
        if (currentList == null)
          throw new IllegalArgumentException(
              "no multi-instance field at '" + leaf.key() + "' on the instance");
        if (leaf.index() < 0 || leaf.index() >= currentList.size())
          throw new IllegalArgumentException(
              "multi-instance field '" + leaf.key() + "' index " + leaf.index()
                  + " out of range (list has " + currentList.size() + " entries)");
        List<FieldInstanceArtifact> updated = new ArrayList<>(currentList);
        updated.remove((int) leaf.index());
        replaceMultiField.accept(leaf.key(), updated);
      }
      case CLEAR_MULTI_FIELD -> {
        if (!multiFields.containsKey(leaf.key()))
          throw new IllegalArgumentException(
              "no multi-instance field at '" + leaf.key() + "' on the instance");
        replaceMultiField.accept(leaf.key(), List.of());
      }
      case DELETE_MULTI_ELEMENT_ENTRY -> {
        List<ElementInstanceArtifact> currentList = multiElements.get(leaf.key());
        if (currentList == null)
          throw new IllegalArgumentException(
              "no multi-instance element at '" + leaf.key() + "' on the instance");
        if (leaf.index() < 0 || leaf.index() >= currentList.size())
          throw new IllegalArgumentException(
              "multi-instance element '" + leaf.key() + "' index " + leaf.index()
                  + " out of range (list has " + currentList.size() + " entries)");
        List<ElementInstanceArtifact> updated = new ArrayList<>(currentList);
        updated.remove((int) leaf.index());
        replaceMultiElements.accept(leaf.key(), updated);
      }
      case CLEAR_MULTI_ELEMENT -> {
        if (!multiElements.containsKey(leaf.key()))
          throw new IllegalArgumentException(
              "no multi-instance element at '" + leaf.key() + "' on the instance");
        replaceMultiElements.accept(leaf.key(), List.of());
      }
    }
  }
}

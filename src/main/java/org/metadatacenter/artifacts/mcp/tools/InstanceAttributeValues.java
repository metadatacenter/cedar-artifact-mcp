package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Walks a CEDAR template instance to the attribute-value <em>field</em> named by a
 * {@code field_path} and rewrites that field's group of dynamic name→value entries
 * (its {@code attributeValueFieldInstanceGroups} entry) on the parent instance that holds it.
 *
 * <p>The final path segment names the attribute-value field (the group); the segments before it
 * descend through single- or multi-instance elements exactly as {@link InstanceFieldValues} does.
 * The group is rebuilt with the library's {@code withoutAttributeValueFieldGroup} +
 * {@code withAttributeValueFieldGroup} pair (which keep the instance's flat child-key namespace
 * consistent and reject collisions). Artifacts are immutable, so each level on the way down is
 * rebuilt via its {@code builder(existing)} constructor.
 */
final class InstanceAttributeValues
{
  private InstanceAttributeValues() {}

  /**
   * Apply {@code groupOp} to the attribute-value group named by the last path segment, on the
   * parent instance {@code path} resolves to, and return the rebuilt template instance.
   *
   * @throws IllegalArgumentException if the path is malformed, an intermediate step doesn't
   *   resolve to an element, the attribute-value segment carries an index, or rebuilding the
   *   group hits a child-key collision.
   */
  static TemplateInstanceArtifact apply(
      TemplateInstanceArtifact instance,
      String path,
      UnaryOperator<LinkedHashMap<String, FieldInstanceArtifact>> groupOp)
  {
    return updateTemplate(instance, SchemaPaths.parse(path), groupOp);
  }

  private static TemplateInstanceArtifact updateTemplate(
      TemplateInstanceArtifact instance,
      List<SchemaPaths.Segment> path,
      UnaryOperator<LinkedHashMap<String, FieldInstanceArtifact>> groupOp)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      requireNoIndex(head);
      LinkedHashMap<String, FieldInstanceArtifact> group =
          groupOp.apply(currentGroup(instance.attributeValueFieldInstanceGroups(), head.key()));
      TemplateInstanceArtifact.Builder builder = TemplateInstanceArtifact.builder(instance);
      if (instance.attributeValueFieldInstanceGroups().containsKey(head.key()))
        builder.withoutAttributeValueFieldGroup(head.key());
      return builder.withAttributeValueFieldGroup(head.key(), group).build();
    }

    List<SchemaPaths.Segment> rest = path.subList(1, path.size());
    if (head.hasIndex()) {
      List<ElementInstanceArtifact> list = instance.multiInstanceElementInstances().get(head.key());
      List<ElementInstanceArtifact> updated = updateElementInList(list, head, rest, groupOp);
      return TemplateInstanceArtifact.builder(instance)
          .replaceMultiInstanceElementInstances(head.key(), updated).build();
    }
    ElementInstanceArtifact child = instance.singleInstanceElementInstances().get(head.key());
    if (child == null)
      throw new IllegalArgumentException("no single-instance element at '" + head.key() + "' on the instance");
    return TemplateInstanceArtifact.builder(instance)
        .replaceSingleInstanceElementInstance(head.key(), updateElement(child, rest, groupOp)).build();
  }

  private static ElementInstanceArtifact updateElement(
      ElementInstanceArtifact element,
      List<SchemaPaths.Segment> path,
      UnaryOperator<LinkedHashMap<String, FieldInstanceArtifact>> groupOp)
  {
    SchemaPaths.Segment head = path.get(0);

    if (path.size() == 1) {
      requireNoIndex(head);
      LinkedHashMap<String, FieldInstanceArtifact> group =
          groupOp.apply(currentGroup(element.attributeValueFieldInstanceGroups(), head.key()));
      ElementInstanceArtifact.Builder builder = ElementInstanceArtifact.builder(element);
      if (element.attributeValueFieldInstanceGroups().containsKey(head.key()))
        builder.withoutAttributeValueFieldGroup(head.key());
      return builder.withAttributeValueFieldGroup(head.key(), group).build();
    }

    List<SchemaPaths.Segment> rest = path.subList(1, path.size());
    if (head.hasIndex()) {
      List<ElementInstanceArtifact> list = element.multiInstanceElementInstances().get(head.key());
      List<ElementInstanceArtifact> updated = updateElementInList(list, head, rest, groupOp);
      return ElementInstanceArtifact.builder(element)
          .replaceMultiInstanceElementInstances(head.key(), updated).build();
    }
    ElementInstanceArtifact child = element.singleInstanceElementInstances().get(head.key());
    if (child == null)
      throw new IllegalArgumentException("no single-instance element at '" + head.key() + "' on element");
    return ElementInstanceArtifact.builder(element)
        .replaceSingleInstanceElementInstance(head.key(), updateElement(child, rest, groupOp)).build();
  }

  private static List<ElementInstanceArtifact> updateElementInList(
      List<ElementInstanceArtifact> list, SchemaPaths.Segment head,
      List<SchemaPaths.Segment> rest,
      UnaryOperator<LinkedHashMap<String, FieldInstanceArtifact>> groupOp)
  {
    if (list == null)
      throw new IllegalArgumentException("no multi-instance element at '" + head.key() + "' on the instance");
    if (head.index() < 0 || head.index() >= list.size())
      throw new IllegalArgumentException("multi-instance element '" + head.key() + "' index "
          + head.index() + " out of range (list has " + list.size() + " entries)");
    List<ElementInstanceArtifact> updated = new ArrayList<>(list);
    updated.set(head.index(), updateElement(list.get(head.index()), rest, groupOp));
    return updated;
  }

  private static LinkedHashMap<String, FieldInstanceArtifact> currentGroup(
      Map<String, Map<String, FieldInstanceArtifact>> groups, String groupKey)
  {
    return new LinkedHashMap<>(groups.getOrDefault(groupKey, Map.of()));
  }

  private static void requireNoIndex(SchemaPaths.Segment head)
  {
    if (head.hasIndex())
      throw new IllegalArgumentException("attribute-value field '" + head.key()
          + "' is not multi-instance; drop the [index] from the path");
  }
}

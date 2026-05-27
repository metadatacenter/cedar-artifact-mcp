package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Walks a CEDAR template instance to a slash-separated {@code field_path}, transforms
 * the leaf {@link FieldInstanceArtifact}, and returns a new instance with the result
 * substituted in place.
 *
 * <p>Artifacts are immutable; each level on the way down to the leaf gets rebuilt via
 * its {@code builder(existing)} constructor with the matching child removed and re-added.
 *
 * <p>Only single-instance children are walked through — multi-instance children are out
 * of scope for the first-cut setter tools. Callers checking for that should rely on the
 * exception thrown here when the named key isn't a single-instance field/element.
 */
final class InstanceFieldValues
{
  private InstanceFieldValues() {}

  /**
   * @throws IllegalArgumentException if {@code path} is empty, blank-segmented, or
   *   doesn't resolve to a single-instance field at the leaf / single-instance element
   *   at any intermediate step.
   */
  static TemplateInstanceArtifact apply(
      TemplateInstanceArtifact instance,
      String path,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    List<String> parts = parsePath(path);
    return updateTemplate(instance, parts, transform);
  }

  private static List<String> parsePath(String path)
  {
    if (path == null || path.isBlank())
      throw new IllegalArgumentException("field_path is required and must not be blank");
    String[] raw = path.split("/");
    for (String p : raw)
      if (p.isBlank())
        throw new IllegalArgumentException("field_path segments must be non-empty (got '" + path + "')");
    return Arrays.asList(raw);
  }

  private static TemplateInstanceArtifact updateTemplate(
      TemplateInstanceArtifact instance,
      List<String> path,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    String head = path.get(0);

    if (path.size() == 1) {
      FieldInstanceArtifact current = instance.singleInstanceFieldInstances().get(head);
      if (current == null)
        throw new IllegalArgumentException(
            "no single-instance field at '" + head + "' on the instance");
      FieldInstanceArtifact updated = transform.apply(current);
      return TemplateInstanceArtifact.builder(instance)
          .withoutSingleInstanceFieldInstance(head)
          .withSingleInstanceFieldInstance(head, updated)
          .build();
    }

    ElementInstanceArtifact childElement = instance.singleInstanceElementInstances().get(head);
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head + "' on the instance");
    ElementInstanceArtifact updatedChild = updateElement(childElement, path.subList(1, path.size()), transform);
    return TemplateInstanceArtifact.builder(instance)
        .withoutSingleInstanceElementInstance(head)
        .withSingleInstanceElementInstance(head, updatedChild)
        .build();
  }

  private static ElementInstanceArtifact updateElement(
      ElementInstanceArtifact element,
      List<String> path,
      Function<FieldInstanceArtifact, FieldInstanceArtifact> transform)
  {
    String head = path.get(0);

    if (path.size() == 1) {
      FieldInstanceArtifact current = element.singleInstanceFieldInstances().get(head);
      if (current == null)
        throw new IllegalArgumentException(
            "no single-instance field at '" + head + "' on element");
      FieldInstanceArtifact updated = transform.apply(current);
      return ElementInstanceArtifact.builder(element)
          .withoutSingleInstanceFieldInstance(head)
          .withSingleInstanceFieldInstance(head, updated)
          .build();
    }

    ElementInstanceArtifact childElement = element.singleInstanceElementInstances().get(head);
    if (childElement == null)
      throw new IllegalArgumentException(
          "no single-instance element at '" + head + "' on element");
    ElementInstanceArtifact updatedChild = updateElement(childElement, path.subList(1, path.size()), transform);
    return ElementInstanceArtifact.builder(element)
        .withoutSingleInstanceElementInstance(head)
        .withSingleInstanceElementInstance(head, updatedChild)
        .build();
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ParentSchemaArtifact;

/**
 * Resolve a slash-separated {@code field_path} against a CEDAR template or element
 * schema. The leaf segment names a single-instance field; intermediate segments name
 * single-instance elements. Multi-instance children aren't walked (the setter tools
 * don't support indexing into them yet).
 */
final class SchemaPaths
{
  private SchemaPaths() {}

  /**
   * @throws IllegalArgumentException if the path is blank, has blank segments, or
   *   doesn't resolve to a field at the leaf through single-instance element steps.
   */
  static FieldSchemaArtifact resolveField(ParentSchemaArtifact root, String path)
  {
    if (path == null || path.isBlank())
      throw new IllegalArgumentException("field_path is required and must not be blank");
    String[] parts = path.split("/");
    for (String p : parts)
      if (p.isBlank())
        throw new IllegalArgumentException("field_path segments must be non-empty (got '" + path + "')");

    ParentSchemaArtifact current = root;
    for (int i = 0; i < parts.length - 1; i++) {
      String key = parts[i];
      if (!current.isElement(key))
        throw new IllegalArgumentException(
            "no element child '" + key + "' on schema (looking up field_path '" + path + "')");
      ElementSchemaArtifact next = current.getElementSchemaArtifact(key);
      if (next.isMultiple())
        throw new IllegalArgumentException(
            "field_path walks through multi-instance element '" + key
                + "' — only single-instance elements are supported");
      current = next;
    }
    String leafKey = parts[parts.length - 1];
    if (!current.isField(leafKey))
      throw new IllegalArgumentException(
          "no field child '" + leafKey + "' on schema (looking up field_path '" + path + "')");
    FieldSchemaArtifact field = current.getFieldSchemaArtifact(leafKey);
    if (field.isMultiple())
      throw new IllegalArgumentException(
          "field_path resolves to multi-instance field '" + leafKey
              + "' — only single-instance fields are supported");
    return field;
  }
}

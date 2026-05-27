package org.metadatacenter.artifacts.mcp.tools;

import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ParentSchemaArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolve a slash-separated {@code field_path} against a CEDAR template or element
 * schema. The leaf segment names a field; intermediate segments name elements.
 *
 * <p>Each segment may carry a bracketed integer index ({@code [N]}) when the named
 * child is multi-instance — e.g. {@code addresses[2]/street}, {@code emails[0]}. The
 * index is positional and 0-based. For schema resolution the index is informational
 * only (the schema doesn't change with index); the {@link InstanceFieldValues}
 * walker honours the index when navigating the instance side.
 */
final class SchemaPaths
{
  private static final Pattern SEGMENT = Pattern.compile("^([^\\[]+)(?:\\[(\\d+)])?$");

  private SchemaPaths() {}

  /** One parsed path segment: a child key plus an optional 0-based index. */
  record Segment(String key, Integer index)
  {
    boolean hasIndex() { return index != null; }
  }

  /**
   * Parse a slash-separated path into segments.
   *
   * @throws IllegalArgumentException if the path is blank, has empty segments, or any
   *   segment doesn't match {@code <key>} or {@code <key>[<integer>]}.
   */
  static List<Segment> parse(String path)
  {
    if (path == null || path.isBlank())
      throw new IllegalArgumentException("field_path is required and must not be blank");
    String[] raw = path.split("/");
    List<Segment> segments = new ArrayList<>(raw.length);
    for (String part : raw) {
      if (part.isBlank())
        throw new IllegalArgumentException(
            "field_path segments must be non-empty (got '" + path + "')");
      Matcher m = SEGMENT.matcher(part);
      if (!m.matches())
        throw new IllegalArgumentException(
            "field_path segment '" + part + "' is malformed; expected '<key>' or '<key>[<index>]'");
      String key = m.group(1);
      Integer index = m.group(2) == null ? null : Integer.parseInt(m.group(2));
      segments.add(new Segment(key, index));
    }
    return segments;
  }

  /**
   * Resolve a slash-separated path to the {@link FieldSchemaArtifact} at the leaf.
   *
   * <p>Intermediate segments must name elements (single-instance unless an
   * {@code [N]} index is supplied); the leaf must name a field (single-instance
   * unless indexed). Bracketed indices are accepted but their values don't affect
   * schema resolution — the schema is shared across all multi-instance members.
   *
   * @throws IllegalArgumentException on malformed segments, missing children, or a
   *   mismatch between a segment's indexing and the schema's {@code isMultiple()}.
   */
  static FieldSchemaArtifact resolveField(ParentSchemaArtifact root, String path)
  {
    List<Segment> segments = parse(path);
    ParentSchemaArtifact current = root;
    for (int i = 0; i < segments.size() - 1; i++) {
      Segment seg = segments.get(i);
      if (!current.isElement(seg.key()))
        throw new IllegalArgumentException(
            "no element child '" + seg.key() + "' on schema (looking up field_path '" + path + "')");
      ElementSchemaArtifact next = current.getElementSchemaArtifact(seg.key());
      checkSegmentMatchesCardinality(seg, next.isMultiple(), "element");
      current = next;
    }
    Segment leaf = segments.get(segments.size() - 1);
    if (!current.isField(leaf.key()))
      throw new IllegalArgumentException(
          "no field child '" + leaf.key() + "' on schema (looking up field_path '" + path + "')");
    FieldSchemaArtifact field = current.getFieldSchemaArtifact(leaf.key());
    checkSegmentMatchesCardinality(leaf, field.isMultiple(), "field");
    return field;
  }

  private static void checkSegmentMatchesCardinality(Segment seg, boolean isMultiple, String kind)
  {
    if (isMultiple && !seg.hasIndex())
      throw new IllegalArgumentException(
          "multi-instance " + kind + " '" + seg.key() + "' requires an index (e.g. '"
              + seg.key() + "[0]')");
    if (!isMultiple && seg.hasIndex())
      throw new IllegalArgumentException(
          "single-instance " + kind + " '" + seg.key() + "' must not carry an index");
  }
}

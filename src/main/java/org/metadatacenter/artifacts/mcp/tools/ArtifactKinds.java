package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.model.ModelNodeNames;

/**
 * Detects which kind of CEDAR artifact an incoming JSON object is, so the auto-detecting
 * {@code validate_schema_artifact} tool can dispatch to the matching {@link
 * org.metadatacenter.model.validation.CedarValidator} method (or redirect an instance to
 * {@code validate_instance_artifact}).
 *
 * <p>Templates, elements, and fields are identified by their JSON-LD {@code @type} IRI (a
 * static field is still a field for validation purposes). Instances are not {@code @type}-keyed
 * — their {@code @type} is the template's instance type when present at all — so an artifact with
 * no schema-artifact {@code @type} but a {@code schema:isBasedOn} is taken to be an instance.
 */
final class ArtifactKinds
{
  private ArtifactKinds() {}

  enum Kind
  {
    TEMPLATE, ELEMENT, FIELD, INSTANCE
  }

  /**
   * Best-effort kind detection. Returns {@code null} when the kind can't be determined (no
   * recognized {@code @type} and no {@code schema:isBasedOn}) — callers then fall back to
   * running the requested validator, which surfaces the real diagnostics.
   */
  static Kind detect(ObjectNode node)
  {
    String typeIri = firstType(node);
    if (typeIri != null) {
      if (ModelNodeNames.TEMPLATE_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri))
        return Kind.TEMPLATE;
      if (ModelNodeNames.ELEMENT_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri))
        return Kind.ELEMENT;
      if (ModelNodeNames.FIELD_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri)
          || ModelNodeNames.STATIC_FIELD_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri))
        return Kind.FIELD;
    }
    // No schema-artifact @type: an isBasedOn marks a template instance.
    if (node.hasNonNull(ModelNodeNames.SCHEMA_IS_BASED_ON))
      return Kind.INSTANCE;
    return null;
  }

  /** The JSON-LD {@code @type}, which CEDAR allows to be a string or an array of strings. */
  private static String firstType(ObjectNode node)
  {
    JsonNode typeNode = node.path("@type");
    if (typeNode.isTextual())
      return typeNode.asText();
    if (typeNode.isArray() && typeNode.size() >= 1 && typeNode.get(0).isTextual())
      return typeNode.get(0).asText();
    return null;
  }
}

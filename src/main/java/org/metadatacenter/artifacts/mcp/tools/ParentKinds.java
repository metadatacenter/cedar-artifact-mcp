package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.model.ModelNodeNames;

/**
 * Inspects an incoming CEDAR JSON Schema artifact and tells callers whether it's a
 * template or an element parent. Shared by the {@code add_field} and {@code add_element}
 * tools, both of which dispatch on parent kind when calling into the library's
 * reader / builder / renderer triangle.
 */
final class ParentKinds
{
  private ParentKinds() {}

  enum ParentKind {TEMPLATE, ELEMENT}

  /**
   * Read the JSON-LD {@code @type} URI off a parent object and map it to a parent kind.
   * CEDAR's {@code @type} can be a string or an array of strings; only the schema-artifact
   * IRIs ({@code Template} or {@code TemplateElement}) are accepted.
   *
   * @throws IllegalArgumentException if {@code @type} is missing, the wrong shape, or an
   *   IRI that doesn't correspond to a template or element artifact.
   */
  static ParentKind detect(ObjectNode parent)
  {
    JsonNode typeNode = parent.path("@type");
    if (typeNode.isMissingNode() || typeNode.isNull())
      throw new IllegalArgumentException("parent JSON missing @type — not a CEDAR schema artifact");

    String typeIri;
    if (typeNode.isTextual()) {
      typeIri = typeNode.asText();
    } else if (typeNode.isArray() && typeNode.size() >= 1 && typeNode.get(0).isTextual()) {
      typeIri = typeNode.get(0).asText();
    } else {
      throw new IllegalArgumentException("parent JSON @type must be a string or array of strings");
    }

    if (ModelNodeNames.TEMPLATE_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri))
      return ParentKind.TEMPLATE;
    if (ModelNodeNames.ELEMENT_SCHEMA_ARTIFACT_TYPE_IRI.equals(typeIri))
      return ParentKind.ELEMENT;

    throw new IllegalArgumentException("parent JSON @type \"" + typeIri
        + "\" is not a CEDAR template or element artifact type");
  }
}

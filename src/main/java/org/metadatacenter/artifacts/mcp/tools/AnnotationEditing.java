package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.List;
import java.util.function.Consumer;

/**
 * Shared machinery for the annotation tools ({@code set_literal_annotation},
 * {@code set_iri_annotation}, {@code remove_annotation}). An annotation is a property-IRI → value
 * pair attached to an artifact's <em>root</em>; in the model it is an {@code Annotations} map
 * whose values are literal ({@code @value}) or IRI ({@code @id}), and on the wire it is a
 * top-level {@code annotations} object. All four annotatable kinds (template, element, field,
 * template instance) carry it identically, so these tools work at the JSON-node level — read the
 * artifact (kind auto-detected), edit the {@code annotations} map, validate, and re-render YAML —
 * rather than dispatching through every concrete builder. Element instances do not carry
 * annotations and are rejected.
 */
final class AnnotationEditing
{
  // The JSON-LD wire key (ModelNodeNames.ANNOTATIONS) — distinct from the YAML key "annotations".
  // These tools edit the JSON-LD node produced by ArtifactExchange.toObjectNode, so they key off
  // the JSON-LD form; the YAML renderer maps it back to "annotations" on output.
  static final String ANNOTATIONS = "_annotations";
  static final String JSON_LD_VALUE = "@value";
  static final String JSON_LD_ID = "@id";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private AnnotationEditing() {}

  static ObjectNode literalValue(String value)
  {
    return MAPPER.createObjectNode().put(JSON_LD_VALUE, value);
  }

  static ObjectNode iriValue(String iri)
  {
    return MAPPER.createObjectNode().put(JSON_LD_ID, iri);
  }

  /**
   * Read the artifact (YAML or JSON; kind auto-detected), apply {@code mutate} to its top-level
   * {@code annotations} map (created if absent, dropped if left empty), validate a rendered schema
   * artifact with {@link CedarValidator} (Principle 6; instances validate only against a
   * template, so they are not validated here), and return the updated artifact as expanded YAML.
   */
  static McpSchema.CallToolResult apply(String artifactText, Consumer<ObjectNode> mutate)
  {
    if (artifactText == null || artifactText.isBlank())
      return error("artifact is required and must not be blank");

    ObjectNode node;
    try {
      node = ArtifactExchange.toObjectNode(artifactText);
    } catch (RuntimeException e) {
      return error("artifact could not be parsed as a CEDAR artifact (YAML or JSON): "
          + e.getMessage());
    }

    ArtifactKinds.Kind kind = ArtifactKinds.detect(node);
    if (kind == null)
      return error("annotations are supported on a CEDAR template, element, field, or template "
          + "instance, but the artifact's kind could not be determined from its @type (note: "
          + "element instances do not carry annotations)");

    ObjectNode annotations = node.has(ANNOTATIONS) && node.get(ANNOTATIONS).isObject()
        ? (ObjectNode) node.get(ANNOTATIONS)
        : MAPPER.createObjectNode();
    mutate.accept(annotations);
    if (annotations.isEmpty())
      node.remove(ANNOTATIONS);
    else
      node.set(ANNOTATIONS, annotations);

    try {
      ValidationReport report = switch (kind) {
        case TEMPLATE -> VALIDATOR.validateTemplate(node);
        case ELEMENT -> VALIDATOR.validateTemplateElement(node);
        case FIELD -> VALIDATOR.validateTemplateField(node);
        case INSTANCE -> null;
      };
      if (report != null && !"true".equals(report.getValidationStatus()))
        return error("the annotated artifact failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating the annotated artifact: "
          + e.getMessage());
    }

    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(node);
    } catch (RuntimeException e) {
      return error("failed to render the annotated artifact as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  static String stringArg(java.util.Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }

  private static String formatErrors(ValidationReport report)
  {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (ErrorItem err : report.getErrors()) {
      if (i++ > 0) sb.append("; ");
      sb.append(err.toString());
      if (i >= 5) {
        sb.append("; ... (").append(report.getErrors().size() - i).append(" more)");
        break;
      }
    }
    return sb.length() == 0 ? "(no error details)" : sb.toString();
  }
}

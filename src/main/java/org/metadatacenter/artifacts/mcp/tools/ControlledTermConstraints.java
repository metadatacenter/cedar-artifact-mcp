package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.fields.FieldInputType;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.List;
import java.util.function.Consumer;

/**
 * Shared plumbing for the four {@code add_*_constraint} tools.
 *
 * <p>Each constraint tool reads a controlled-term field JSON, mutates the field's
 * builder to attach a constraint, then renders and revalidates. The variable bit is
 * the builder mutation; everything else is the same. {@link #apply} captures that
 * pipeline so the per-tool handlers stay focused on parsing arguments and choosing
 * which {@code with*ValueConstraint} method to call.
 */
final class ControlledTermConstraints
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private ControlledTermConstraints() {}

  /**
   * Read {@code fieldJsonText} as a controlled-term field, apply {@code mutator} to
   * its builder, then render and revalidate. Returns the updated field as JSON or an
   * {@code isError=true} content block on any failure.
   */
  static McpSchema.CallToolResult apply(
      String fieldJsonText, Consumer<ControlledTermField.ControlledTermFieldBuilder> mutator)
  {
    if (fieldJsonText == null || fieldJsonText.isBlank())
      return error("field is required and must not be blank");

    ObjectNode fieldObject;
    try {
      fieldObject = ArtifactExchange.toObjectNode(fieldJsonText);
    } catch (RuntimeException e) {
      return error("field parse failed: " + e.getMessage());
    }

    FieldSchemaArtifact field;
    try {
      field = READER.readFieldSchemaArtifact(fieldObject);
    } catch (ArtifactParseException e) {
      return error("field rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    // An *empty* controlled-term-field renders to JSON that's indistinguishable from a
    // text-field (the library's reader only classifies a TEXTFIELD as ControlledTermField
    // once it carries a controlled-term value constraint). So we accept any TEXTFIELD-shape
    // input — text-field or controlled-term-field — and (re-)build it as a controlled-term
    // field with the new constraint attached.
    if (field.fieldUi().inputType() != FieldInputType.TEXTFIELD)
      return error("field must be a text-field or controlled-term-field (got input type "
          + field.fieldUi().inputType() + ")");

    ControlledTermField updated;
    try {
      ControlledTermField.ControlledTermFieldBuilder builder = controlledTermBuilderFrom(field);
      mutator.accept(builder);
      updated = builder.build();
    } catch (RuntimeException e) {
      return error("constraint apply failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderFieldSchemaArtifact(updated);

    try {
      ValidationReport report = VALIDATOR.validateTemplateField(rendered);
      if (!"true".equals(report.getValidationStatus()))
        return error("updated field failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating updated field: " + e.getMessage());
    }

    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated field as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  /**
   * Return a {@link ControlledTermField.ControlledTermFieldBuilder} seeded from the given
   * field's metadata. When {@code field} is already a {@link ControlledTermField} we use
   * the library's clone-builder; for any other TEXTFIELD-shape field (typically a freshly
   * created empty controlled-term-field that round-tripped to {@code TextField}), we copy
   * the public metadata into a fresh builder.
   */
  private static ControlledTermField.ControlledTermFieldBuilder controlledTermBuilderFrom(
      FieldSchemaArtifact field)
  {
    if (field instanceof ControlledTermField ctf)
      return ControlledTermField.builder(ctf);

    ControlledTermField.ControlledTermFieldBuilder b = ControlledTermField.builder()
        .withName(field.name())
        .withDescription(field.description());
    field.identifier().ifPresent(b::withIdentifier);
    field.version().ifPresent(b::withVersion);
    field.status().ifPresent(b::withStatus);
    field.previousVersion().ifPresent(b::withPreviousVersion);
    field.derivedFrom().ifPresent(b::withDerivedFrom);
    field.jsonLdId().ifPresent(b::withJsonLdId);
    if (field.isMultiple()) b.withIsMultiple(true);
    field.minItems().ifPresent(b::withMinItems);
    field.maxItems().ifPresent(b::withMaxItems);
    field.propertyUri().ifPresent(b::withPropertyUri);
    field.language().ifPresent(b::withLanguage);
    field.preferredLabel().ifPresent(b::withPreferredLabel);
    field.createdBy().ifPresent(b::withCreatedBy);
    field.modifiedBy().ifPresent(b::withModifiedBy);
    field.createdOn().ifPresent(b::withCreatedOn);
    field.lastUpdatedOn().ifPresent(b::withLastUpdatedOn);
    field.annotations().ifPresent(b::withAnnotations);
    return b;
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

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

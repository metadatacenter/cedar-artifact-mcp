package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.DoiField;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.LinkField;
import org.metadatacenter.artifacts.model.core.NihGrantIdField;
import org.metadatacenter.artifacts.model.core.OrcidField;
import org.metadatacenter.artifacts.model.core.PfasField;
import org.metadatacenter.artifacts.model.core.PubMedField;
import org.metadatacenter.artifacts.model.core.RorField;
import org.metadatacenter.artifacts.model.core.RridField;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_iri_default_value} — sets the schema-level default value on
 * an IRI-valued field (link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, DOI) or
 * a controlled-term field. Controlled-term defaults always carry a label alongside
 * the class IRI; plain IRI-field schema defaults are bare URIs (no label) — distinct
 * from instance-level IRI values, which take an optional rdfs:label via
 * {@code set_iri_field_value}.
 *
 * <p>The controlled-term branch requires the schema to already declare the field as
 * controlled-term — at least one class/ontology/branch/value-set constraint must be
 * attached. Same wire collision as the rest of the controlled-term tooling: a
 * TEXTFIELD without a constraint isn't classified as ControlledTermField on JSON
 * round-trip.
 */
public final class SetIriDefaultValueTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private SetIriDefaultValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field", Map.of(
        "type", "string",
        "description",
        "CEDAR IRI field as YAML — link, ROR, ORCID, PFAS, RRID, PubMed, "
            + "NIH-grant-ID, or DOI (the kind 'create_field' with one of those types "
            + "returns) — or a controlled-term field already carrying at least one "
            + "constraint. JSON Schema is also accepted."));
    properties.put("label", Map.of(
        "type", "string",
        "description",
        "Human-readable label for the default. Required for controlled-term fields "
            + "(their defaults carry IRI + label); not accepted for plain IRI fields, "
            + "whose schema defaults are bare URIs."));
    properties.put("iri", Map.of(
        "type", "string",
        "description", "Default URI value."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("field", "iri"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_iri_default_value")
        .title("Set an IRI default value on a field")
        .description(
            "Attaches a default URI value to an IRI-valued CEDAR field schema — "
                + "link/ROR/ORCID/etc. (bare URI) or controlled-term (URI + label, "
                + "requires the field to already carry a constraint). "
                + "Returns the updated field as expanded YAML, re-validated with "
                + "CedarValidator." + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJsonText = stringArg(args, "field");
    if (fieldJsonText == null || fieldJsonText.isBlank())
      return error("field is required and must not be blank");

    String iriArg = stringArg(args, "iri");
    if (iriArg == null || iriArg.isBlank())
      return error("iri is required and must not be blank");

    URI iri;
    try {
      iri = new URI(iriArg);
    } catch (URISyntaxException e) {
      return error("iri is not a valid URI: " + e.getMessage());
    }

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

    String label = stringArg(args, "label");  // controlled-term only
    if (field instanceof ControlledTermField) {
      if (label == null || label.isBlank())
        return error("label is required for a controlled-term field — its default "
            + "carries the class IRI and a human-readable label");
      return ControlledTermConstraints.apply(fieldJsonText, builder ->
          builder.withDefaultValue(iri, label));
    }
    if (label != null && !label.isBlank())
      return error("label applies only to controlled-term fields — plain IRI-field "
          + "schema defaults are bare URIs. If this field is meant to be "
          + "controlled-term, attach a constraint first via set_class_constraint / "
          + "set_ontology_constraint / set_branch_constraint / set_valueset_constraint.");

    FieldSchemaArtifact updated;
    try {
      updated = rebuildWithDefault(field, iri);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("set_iri_default_value failed: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
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

  private static FieldSchemaArtifact rebuildWithDefault(FieldSchemaArtifact field, URI iri)
  {
    if (field instanceof LinkField lf) return LinkField.builder(lf).withDefaultValue(iri).build();
    if (field instanceof RorField rf) return RorField.builder(rf).withDefaultValue(iri).build();
    if (field instanceof OrcidField of) return OrcidField.builder(of).withDefaultValue(iri).build();
    if (field instanceof PfasField pf) return PfasField.builder(pf).withDefaultValue(iri).build();
    if (field instanceof RridField rf) return RridField.builder(rf).withDefaultValue(iri).build();
    if (field instanceof PubMedField pmf) return PubMedField.builder(pmf).withDefaultValue(iri).build();
    if (field instanceof NihGrantIdField nf) return NihGrantIdField.builder(nf).withDefaultValue(iri).build();
    if (field instanceof DoiField df) return DoiField.builder(df).withDefaultValue(iri).build();
    throw new IllegalArgumentException("set_iri_default_value works only on IRI fields "
        + "(link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, DOI) and "
        + "controlled-term fields; got " + field.getClass().getSimpleName());
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

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.CheckboxField;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.EmailField;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ListField;
import org.metadatacenter.artifacts.model.core.NumericField;
import org.metadatacenter.artifacts.model.core.PhoneNumberField;
import org.metadatacenter.artifacts.model.core.RadioField;
import org.metadatacenter.artifacts.model.core.TemporalField;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code add_default_value} — sets the schema-level default value on a
 * literal-valued field (text, numeric, temporal, phone, email, radio, checkbox,
 * list). For IRI fields use {@link AddIriDefaultValueTool}; for controlled-term
 * fields use {@link AddControlledTermDefaultValueTool}.
 *
 * <p>Text-area fields are not supported here — the library's {@code TextAreaField.Builder}
 * doesn't expose {@code withDefaultValue} as of the tracked snapshot.
 */
public final class AddDefaultValueTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private AddDefaultValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("field_json", Map.of(
        "type", "string",
        "description",
        "CEDAR field as JSON Schema (the kind 'create_field' with a literal-valued "
            + "type or 'field_from_yaml' returns)."));
    properties.put("value", Map.of(
        "description",
        "Default value to set. String for text/temporal/phone/email/radio/checkbox/"
            + "list fields; number for numeric fields. Type must match the field's "
            + "input type."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("field_json", "value"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("add_default_value")
        .title("Set a literal default value on a field")
        .description(
            "Attaches a default value to a literal-valued CEDAR field schema. "
                + "Returns the updated field JSON, re-validated with CedarValidator. "
                + "Use add_iri_default_value for link/ROR/ORCID/etc. fields, or "
                + "add_controlled_term_default_value for controlled-term fields.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String fieldJsonText = stringArg(args, "field_json");
    if (fieldJsonText == null || fieldJsonText.isBlank())
      return error("field_json is required and must not be blank");

    if (!args.containsKey("value"))
      return error("value is required");
    Object value = args.get("value");

    JsonNode parsed;
    try {
      parsed = JACKSON2.readTree(fieldJsonText);
    } catch (Exception e) {
      return error("field_json parse failed: " + e.getMessage());
    }
    if (!(parsed instanceof ObjectNode fieldObject))
      return error("field_json must parse to a JSON object");

    FieldSchemaArtifact field;
    try {
      field = READER.readFieldSchemaArtifact(fieldObject);
    } catch (ArtifactParseException e) {
      return error("field_json rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("field reader threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    if (field instanceof ControlledTermField)
      return error("field is a controlled-term field — use add_controlled_term_default_value");

    FieldSchemaArtifact updated;
    try {
      updated = rebuildWithDefault(field, value);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("add_default_value failed: " + e.getClass().getSimpleName()
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

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize updated field: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static FieldSchemaArtifact rebuildWithDefault(FieldSchemaArtifact field, Object value)
  {
    if (field instanceof TextField tf)
      return TextField.builder(tf).withDefaultValue(stringValue(value)).build();
    if (field instanceof NumericField nf)
      return NumericField.builder(nf).withDefaultValue(numericValue(value)).build();
    if (field instanceof TemporalField tf)
      return TemporalField.builder(tf).withDefaultValue(stringValue(value)).build();
    if (field instanceof PhoneNumberField pf)
      return PhoneNumberField.builder(pf).withDefaultValue(stringValue(value)).build();
    if (field instanceof EmailField ef)
      return EmailField.builder(ef).withDefaultValue(stringValue(value)).build();
    if (field instanceof RadioField rf)
      return RadioField.builder(rf).withDefaultValue(stringValue(value)).build();
    if (field instanceof CheckboxField cf)
      return CheckboxField.builder(cf).withDefaultValue(stringValue(value)).build();
    if (field instanceof ListField lf)
      return ListField.builder(lf).withDefaultValue(stringValue(value)).build();
    throw new IllegalArgumentException("add_default_value does not support field class "
        + field.getClass().getSimpleName() + " — use add_iri_default_value, "
        + "add_controlled_term_default_value, or check whether the field type supports "
        + "a default at all (text-area and static fields do not).");
  }

  private static String stringValue(Object value)
  {
    if (value == null)
      throw new IllegalArgumentException("value must not be null");
    return value.toString();
  }

  private static Number numericValue(Object value)
  {
    if (value == null)
      throw new IllegalArgumentException("value must not be null");
    if (value instanceof Number n) return n;
    if (value instanceof String s) {
      try {
        return new BigDecimal(s);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("numeric default value must be a number "
            + "(or a numeric string); got '" + s + "'");
      }
    }
    throw new IllegalArgumentException("numeric default value must be a number (got "
        + value.getClass().getSimpleName() + ")");
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

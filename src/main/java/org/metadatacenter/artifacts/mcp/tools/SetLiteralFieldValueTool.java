package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.CheckboxFieldInstance;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.EmailFieldInstance;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.ListFieldInstance;
import org.metadatacenter.artifacts.model.core.NumericFieldInstance;
import org.metadatacenter.artifacts.model.core.PhoneNumberFieldInstance;
import org.metadatacenter.artifacts.model.core.RadioFieldInstance;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemporalFieldInstance;
import org.metadatacenter.artifacts.model.core.TextAreaFieldInstance;
import org.metadatacenter.artifacts.model.core.TextFieldInstance;
import org.metadatacenter.artifacts.model.core.fields.FieldInputType;
import org.metadatacenter.artifacts.model.core.fields.XsdNumericDatatype;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;
import org.metadatacenter.artifacts.model.core.fields.constraints.NumericValueConstraints;
import org.metadatacenter.artifacts.model.core.fields.constraints.TemporalValueConstraints;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_literal_field_value} — sets the {@code @value} of a literal-valued
 * field instance at a slash-separated {@code field_path}.
 *
 * <p>Takes the template so the schema's input type determines which per-type
 * {@code FieldInstance} builder to use (the instance JSON loses that distinction on
 * round-trip — every field-instance read back is the generic
 * {@code FieldInstanceArtifactRecord}). For IRI-valued and controlled-term fields
 * use {@link SetIriFieldValueTool}.
 */
public final class SetLiteralFieldValueTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private SetLiteralFieldValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "CEDAR template the instance is based on, as YAML. Used to look up the "
            + "field's input type at the given path — the instance loses that "
            + "distinction on round-trip."));
    properties.put("instance", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the kind 'create_template_instance' returns)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the target field. Top-level fields are just the key "
            + "(e.g. 'patient_name'); fields inside nested elements use the element key "
            + "as a prefix (e.g. 'address/street'). Only single-instance fields and "
            + "single-instance element steps are supported."));
    properties.put("value", Map.of(
        "description",
        "Value to set. String for text/text-area/temporal/phone/email/radio/checkbox/"
            + "list fields; number for numeric fields. Type must match the schema's "
            + "input type at the resolved path."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("template", "instance", "field_path", "value"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_literal_field_value")
        .title("Set a literal-valued field on an instance")
        .description(
            "Sets the @value of a literal-valued field instance (text, numeric, "
                + "temporal, phone, email, radio, checkbox, list, text-area) at a "
                + "slash-separated field_path. Returns the updated instance as expanded "
                + "YAML. Use set_iri_field_value for link/ROR/ORCID/etc. and "
                + "controlled-term fields."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template is required and must not be blank");

    String instanceJsonText = stringArg(args, "instance");
    if (instanceJsonText == null || instanceJsonText.isBlank())
      return error("instance is required and must not be blank");

    String fieldPath = stringArg(args, "field_path");
    if (fieldPath == null || fieldPath.isBlank())
      return error("field_path is required and must not be blank");

    if (!args.containsKey("value"))
      return error("value is required");
    Object value = args.get("value");

    ObjectNode templateObject;
    try {
      templateObject = ArtifactExchange.toObjectNode(templateJsonText);
    } catch (RuntimeException e) {
      return error("template parse failed: " + e.getMessage());
    }

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("template parse failed: " + e.getMessage());
    }

    ObjectNode instanceObject;
    try {
      instanceObject = ArtifactExchange.toObjectNode(instanceJsonText);
    } catch (RuntimeException e) {
      return error("instance parse failed: " + e.getMessage());
    }

    TemplateInstanceArtifact instance;
    try {
      instance = READER.readTemplateInstanceArtifact(instanceObject);
    } catch (ArtifactParseException e) {
      return error("instance rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("instance parse failed: " + e.getMessage());
    }

    // A YAML instance is sparse — unset fields are omitted. Inflate it against the template so
    // the target slot exists (and the instance is structurally complete) before setting a value.
    try {
      instance = InstanceInflater.inflate(template, instance);
    } catch (RuntimeException e) {
      return error("instance does not match template (could not inflate): " + e.getMessage());
    }

    FieldSchemaArtifact schemaField;
    try {
      schemaField = SchemaPaths.resolveField(template, fieldPath);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    if (schemaField instanceof ControlledTermField)
      return error("field at '" + fieldPath + "' is a controlled-term field — use "
          + "set_iri_field_value (iri + label) instead");

    FieldInputType inputType = schemaField.fieldUi().inputType();
    if (inputType.isIri())
      return error("field at '" + fieldPath + "' is an IRI field (" + inputType
          + ") — use set_iri_field_value instead");

    FieldInstanceArtifact newFieldInstance;
    try {
      newFieldInstance = buildLiteralFieldInstance(schemaField, inputType, value);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    TemplateInstanceArtifact updated;
    try {
      updated = InstanceFieldValues.apply(instance, fieldPath, existing -> newFieldInstance);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("set_literal_field_value failed: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(updated);
    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated instance as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  private static FieldInstanceArtifact buildLiteralFieldInstance(FieldSchemaArtifact schemaField,
    FieldInputType inputType, Object value)
  {
    return switch (inputType) {
      case TEXTFIELD -> TextFieldInstance.builder().withValue(stringValue(value)).build();
      case TEXTAREA -> TextAreaFieldInstance.builder().withValue(stringValue(value)).build();
      case TEMPORAL -> {
        // Numeric and temporal field instances carry @type alongside @value per CEDAR's
        // typed-literal contract; the template's per-field sub-schema requires both.
        // Thread the declared XsdTemporalDatatype from the schema so the rendered
        // instance keeps its @type after the value is set.
        TemporalFieldInstance.TemporalFieldInstanceBuilder builder =
          TemporalFieldInstance.builder().withValue(stringValue(value));
        XsdTemporalDatatype datatype = schemaField.valueConstraints()
            .filter(TemporalValueConstraints.class::isInstance)
            .map(TemporalValueConstraints.class::cast)
            .map(TemporalValueConstraints::temporalType)
            .orElse(XsdTemporalDatatype.DATETIME);
        builder.withType(datatype);
        yield builder.build();
      }
      case NUMERIC -> {
        NumericFieldInstance.NumericFieldInstanceBuilder builder =
          NumericFieldInstance.builder().withValue(numericValue(value));
        XsdNumericDatatype datatype = schemaField.valueConstraints()
            .filter(NumericValueConstraints.class::isInstance)
            .map(NumericValueConstraints.class::cast)
            .map(NumericValueConstraints::numberType)
            .orElse(XsdNumericDatatype.DECIMAL);
        builder.withType(datatype);
        yield builder.build();
      }
      case PHONE_NUMBER -> PhoneNumberFieldInstance.builder().withValue(stringValue(value)).build();
      case EMAIL -> EmailFieldInstance.builder().withValue(stringValue(value)).build();
      case RADIO -> RadioFieldInstance.builder().withValue(stringValue(value)).build();
      case CHECKBOX -> CheckboxFieldInstance.builder().withValue(stringValue(value)).build();
      case LIST -> ListFieldInstance.builder().withValue(stringValue(value)).build();
      default -> throw new IllegalArgumentException(
          "set_literal_field_value does not support input type " + inputType
              + " — use set_iri_field_value");
    };
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
        throw new IllegalArgumentException("numeric field value must be a number "
            + "(or a numeric string); got '" + s + "'");
      }
    }
    throw new IllegalArgumentException("numeric field value must be a number (got "
        + value.getClass().getSimpleName() + ")");
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

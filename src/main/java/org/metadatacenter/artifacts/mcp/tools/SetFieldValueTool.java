package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_field_value} — sets the {@code @value} of a literal-valued
 * field instance at a slash-separated {@code field_path}.
 *
 * <p>Takes the template so the schema's input type determines which per-type
 * {@code FieldInstance} builder to use (the instance JSON loses that distinction on
 * round-trip — every field-instance read back is the generic
 * {@code FieldInstanceArtifactRecord}). For IRI fields use
 * {@link SetIriFieldValueTool}; for controlled-term fields use
 * {@link SetControlledTermFieldValueTool}.
 */
public final class SetFieldValueTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private SetFieldValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template JSON Schema the instance is based on. Used to look up the "
            + "field's input type at the given path — the instance JSON loses that "
            + "distinction on round-trip."));
    properties.put("instance_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance JSON (the kind 'create_instance' or 'instance_from_yaml' returns)."));
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
        List.of("template_json", "instance_json", "field_path", "value"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_field_value")
        .title("Set a literal-valued field on an instance")
        .description(
            "Sets the @value of a literal-valued field instance (text, numeric, "
                + "temporal, phone, email, radio, checkbox, list, text-area) at a "
                + "slash-separated field_path. Returns the updated instance JSON. Use "
                + "set_iri_field_value for link/ROR/ORCID/etc. fields, or "
                + "set_controlled_term_field_value for controlled-term fields.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template_json");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template_json is required and must not be blank");

    String instanceJsonText = stringArg(args, "instance_json");
    if (instanceJsonText == null || instanceJsonText.isBlank())
      return error("instance_json is required and must not be blank");

    String fieldPath = stringArg(args, "field_path");
    if (fieldPath == null || fieldPath.isBlank())
      return error("field_path is required and must not be blank");

    if (!args.containsKey("value"))
      return error("value is required");
    Object value = args.get("value");

    TemplateSchemaArtifact template;
    try {
      JsonNode parsedTemplate = JACKSON2.readTree(templateJsonText);
      if (!(parsedTemplate instanceof ObjectNode templateObject))
        return error("template_json must parse to a JSON object");
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template_json rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("template_json parse failed: " + e.getMessage());
    }

    TemplateInstanceArtifact instance;
    try {
      JsonNode parsedInstance = JACKSON2.readTree(instanceJsonText);
      if (!(parsedInstance instanceof ObjectNode instanceObject))
        return error("instance_json must parse to a JSON object");
      instance = READER.readTemplateInstanceArtifact(instanceObject);
    } catch (ArtifactParseException e) {
      return error("instance_json rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("instance_json parse failed: " + e.getMessage());
    }

    FieldSchemaArtifact schemaField;
    try {
      schemaField = SchemaPaths.resolveField(template, fieldPath);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    if (schemaField instanceof ControlledTermField)
      return error("field at '" + fieldPath + "' is a controlled-term field — use "
          + "set_controlled_term_field_value instead");

    FieldInputType inputType = schemaField.fieldUi().inputType();
    if (inputType.isIri())
      return error("field at '" + fieldPath + "' is an IRI field (" + inputType
          + ") — use set_iri_field_value instead");

    FieldInstanceArtifact newFieldInstance;
    try {
      newFieldInstance = buildLiteralFieldInstance(inputType, value);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    TemplateInstanceArtifact updated;
    try {
      updated = InstanceFieldValues.apply(instance, fieldPath, existing -> newFieldInstance);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("set_field_value failed: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(updated);
    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize updated instance: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static FieldInstanceArtifact buildLiteralFieldInstance(FieldInputType inputType, Object value)
  {
    return switch (inputType) {
      case TEXTFIELD -> TextFieldInstance.builder().withValue(stringValue(value)).build();
      case TEXTAREA -> TextAreaFieldInstance.builder().withValue(stringValue(value)).build();
      case TEMPORAL -> TemporalFieldInstance.builder().withValue(stringValue(value)).build();
      case NUMERIC -> NumericFieldInstance.builder().withValue(numericValue(value)).build();
      case PHONE_NUMBER -> PhoneNumberFieldInstance.builder().withValue(stringValue(value)).build();
      case EMAIL -> EmailFieldInstance.builder().withValue(stringValue(value)).build();
      case RADIO -> RadioFieldInstance.builder().withValue(stringValue(value)).build();
      case CHECKBOX -> CheckboxFieldInstance.builder().withValue(stringValue(value)).build();
      case LIST -> ListFieldInstance.builder().withValue(stringValue(value)).build();
      default -> throw new IllegalArgumentException(
          "set_field_value does not support input type " + inputType
              + " — use set_iri_field_value or set_controlled_term_field_value");
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

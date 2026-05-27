package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.AttributeValueField;
import org.metadatacenter.artifacts.model.core.CheckboxField;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.DoiField;
import org.metadatacenter.artifacts.model.core.EmailField;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifactBuilder;
import org.metadatacenter.artifacts.model.core.ImageField;
import org.metadatacenter.artifacts.model.core.LinkField;
import org.metadatacenter.artifacts.model.core.ListField;
import org.metadatacenter.artifacts.model.core.NihGrantIdField;
import org.metadatacenter.artifacts.model.core.NumericField;
import org.metadatacenter.artifacts.model.core.OrcidField;
import org.metadatacenter.artifacts.model.core.PageBreakField;
import org.metadatacenter.artifacts.model.core.PfasField;
import org.metadatacenter.artifacts.model.core.PhoneNumberField;
import org.metadatacenter.artifacts.model.core.PubMedField;
import org.metadatacenter.artifacts.model.core.RadioField;
import org.metadatacenter.artifacts.model.core.RichTextField;
import org.metadatacenter.artifacts.model.core.RorField;
import org.metadatacenter.artifacts.model.core.RridField;
import org.metadatacenter.artifacts.model.core.SectionBreakField;
import org.metadatacenter.artifacts.model.core.TemporalField;
import org.metadatacenter.artifacts.model.core.TextAreaField;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.core.YouTubeField;
import org.metadatacenter.artifacts.model.core.fields.TemporalGranularity;
import org.metadatacenter.artifacts.model.core.fields.XsdNumericDatatype;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.artifacts.model.yaml.YamlConstants.FIELD_TYPES;

/**
 * MCP tool {@code create_field} — field variant of {@code create_template}.
 *
 * <p>Builds an empty CEDAR field schema artifact of the requested kebab-case type
 * (e.g. {@code text-field}, {@code controlled-term-field}, {@code numeric-field}) with
 * the supplied name, description, and version. Returns JSON Schema validated with
 * {@link CedarValidator#validateTemplateField}.
 *
 * <p>The full list of {@code type} values comes from the library's
 * {@link org.metadatacenter.artifacts.model.yaml.YamlConstants#FIELD_TYPES} — the same
 * vocabulary {@code field_from_yaml} accepts in YAML {@code type:} discriminators.
 */
public final class CreateFieldTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private CreateFieldTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("name", Map.of(
        "type", "string",
        "description", "Human-readable field name (e.g. \"Patient name\")."));
    properties.put("type", Map.of(
        "type", "string",
        "enum", List.copyOf(FIELD_TYPES),
        "description",
        "Kebab-case CEDAR field type. The same vocabulary 'field_from_yaml' accepts: "
            + "text-field, controlled-term-field, text-area-field, numeric-field, "
            + "temporal-field, radio-field, checkbox-field, single-select-list-field, "
            + "multi-select-list-field, phone-number-field, email-field, link-field, "
            + "attribute-value-field, the ext-* identifier fields (ext-ror-field, "
            + "ext-orcid-field, ext-pfas-field, ext-rrid-field, ext-pubmed-field, "
            + "ext-nih-grant-id-field, ext-doi-field), and the static-* placeholders "
            + "(static-page-break, static-section-break, static-image, static-rich-text, "
            + "static-youtube-video)."));
    properties.put("description", Map.of(
        "type", "string",
        "description", "Free-text description of the field's purpose. Optional; defaults to an empty string."));
    properties.put("version", Map.of(
        "type", "string",
        "description", "Semantic version string in major.minor.patch form (e.g. \"0.0.1\"). Optional; defaults to 0.0.1."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("name", "type"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_field")
        .title("Create CEDAR field")
        .description(
            "Builds an empty CEDAR field schema artifact of the supplied kebab-case type "
                + "(e.g. text-field, controlled-term-field, numeric-field). Returns the "
                + "artifact serialized as JSON. Standalone fields are first-class CEDAR "
                + "artifacts; the returned JSON can be referenced or composed via other "
                + "tools.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String name = stringArg(args, "name");
    if (name == null || name.isBlank())
      return error("name is required and must not be blank");

    String type = stringArg(args, "type");
    if (type == null || type.isBlank())
      return error("type is required and must not be blank");
    if (!FIELD_TYPES.contains(type))
      return error("type \"" + type + "\" is not a known CEDAR field type. Known: " + FIELD_TYPES);

    String description = stringArgOrDefault(args, "description", "");
    String versionText = stringArgOrDefault(args, "version", "0.0.1");

    Version version;
    try {
      version = Version.fromString(versionText);
    } catch (IllegalArgumentException e) {
      return error("invalid version \"" + versionText + "\": " + e.getMessage());
    }

    FieldSchemaArtifact field;
    try {
      FieldSchemaArtifactBuilder<?> builder = builderFor(type);
      builder.withName(name).withDescription(description).withVersion(version);
      field = builder.build();
    } catch (RuntimeException e) {
      return error("field build failed: " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderFieldSchemaArtifact(field);

    try {
      ValidationReport report = VALIDATOR.validateTemplateField(rendered);
      if (!"true".equals(report.getValidationStatus()))
        return error("rendered field failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating rendered field: " + e.getMessage());
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered field: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  /**
   * Dispatch from kebab-case wire name to the per-type builder. The single-select /
   * multi-select list distinction lives in valueConstraints rather than at the field
   * class level, so both wire names map to {@link ListField} with {@code multipleChoice}
   * set accordingly — that's the only configuration this method does beyond returning
   * the bare builder.
   */
  private static FieldSchemaArtifactBuilder<?> builderFor(String type)
  {
    return switch (type) {
      case "text-field" -> TextField.builder();
      case "controlled-term-field" -> ControlledTermField.builder();
      case "text-area-field" -> TextAreaField.builder();
      // Numeric and temporal fields enforce a non-null numberType / temporalGranularity
      // invariant at build time. Pick the most general defaults for an empty shell;
      // callers can override later when value-constraint tools land.
      case "numeric-field" -> NumericField.builder().withNumericType(XsdNumericDatatype.DECIMAL);
      case "temporal-field" -> TemporalField.builder()
          .withTemporalType(XsdTemporalDatatype.DATE)
          .withTemporalGranularity(TemporalGranularity.DAY);
      case "radio-field" -> RadioField.builder();
      case "checkbox-field" -> CheckboxField.builder();
      case "single-select-list-field" -> ListField.builder().withMultipleChoice(false);
      case "multi-select-list-field" -> ListField.builder().withMultipleChoice(true);
      case "phone-number-field" -> PhoneNumberField.builder();
      case "email-field" -> EmailField.builder();
      case "link-field" -> LinkField.builder();
      case "ext-ror-field" -> RorField.builder();
      case "ext-orcid-field" -> OrcidField.builder();
      case "ext-pfas-field" -> PfasField.builder();
      case "ext-rrid-field" -> RridField.builder();
      case "ext-pubmed-field" -> PubMedField.builder();
      case "ext-nih-grant-id-field" -> NihGrantIdField.builder();
      case "ext-doi-field" -> DoiField.builder();
      case "attribute-value-field" -> AttributeValueField.builder();
      case "static-page-break" -> PageBreakField.builder();
      case "static-section-break" -> SectionBreakField.builder();
      case "static-image" -> ImageField.builder();
      case "static-rich-text" -> RichTextField.builder();
      case "static-youtube-video" -> YouTubeField.builder();
      default -> throw new IllegalStateException("unhandled field type: " + type);
    };
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

  private static String stringArgOrDefault(Map<String, Object> args, String key, String fallback)
  {
    String value = stringArg(args, key);
    return value == null ? fallback : value;
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

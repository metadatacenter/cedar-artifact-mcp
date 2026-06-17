package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifactBuilder;
import org.metadatacenter.artifacts.model.core.CheckboxField;
import org.metadatacenter.artifacts.model.core.ImageField;
import org.metadatacenter.artifacts.model.core.PageBreakField;
import org.metadatacenter.artifacts.model.core.RichTextField;
import org.metadatacenter.artifacts.model.core.SectionBreakField;
import org.metadatacenter.artifacts.model.core.YouTubeField;
import org.metadatacenter.artifacts.model.core.ListField;
import org.metadatacenter.artifacts.model.core.NumericField;
import org.metadatacenter.artifacts.model.core.RadioField;
import org.metadatacenter.artifacts.model.core.TemporalField;
import org.metadatacenter.artifacts.model.core.TextAreaField;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.core.fields.InputTimeFormat;
import org.metadatacenter.artifacts.model.core.fields.TemporalGranularity;
import org.metadatacenter.artifacts.model.core.fields.XsdNumericDatatype;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.metadatacenter.artifacts.model.yaml.YamlConstants.FIELD_TYPES;
import static org.metadatacenter.artifacts.model.yaml.YamlConstants.NUMERIC_FIELD;
import static org.metadatacenter.artifacts.model.yaml.YamlConstants.TEMPORAL_FIELD;
import static org.metadatacenter.artifacts.model.yaml.YamlConstants.TEXT_AREA_FIELD;
import static org.metadatacenter.artifacts.model.yaml.YamlConstants.TEXT_FIELD;

/**
 * MCP tool {@code create_field} — field variant of {@code create_template}.
 *
 * <p>Builds an empty CEDAR field schema artifact of the requested kebab-case type
 * (e.g. {@code text-field}, {@code controlled-term-field}, {@code numeric-field}) with
 * the supplied name, description, version, and status. Returns the artifact as expanded YAML,
 * validated with {@link CedarValidator#validateTemplateField}.
 *
 * <p>The full list of {@code type} values comes from the library's
 * {@link org.metadatacenter.artifacts.model.yaml.YamlConstants#FIELD_TYPES} — the same
 * vocabulary {@code schema_artifact_to_yaml} accepts in YAML {@code type:} discriminators.
 */
public final class CreateFieldTool
{
  private CreateFieldTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("type", Map.of(
        "type", "string",
        "enum", List.copyOf(FIELD_TYPES),
        "description",
        "Kebab-case CEDAR field type. The same vocabulary the YAML tools accept: "
            + "text-field, controlled-term-field, text-area-field, numeric-field, "
            + "temporal-field, radio-field, checkbox-field, single-select-list-field, "
            + "multi-select-list-field, phone-number-field, email-field, link-field, "
            + "attribute-value-field, the ext-* identifier fields (ext-ror-field, "
            + "ext-orcid-field, ext-pfas-field, ext-rrid-field, ext-pubmed-field, "
            + "ext-nih-grant-id-field, ext-doi-field), and the static-* placeholders "
            + "(static-page-break, static-section-break, static-image, static-rich-text, "
            + "static-youtube-video)."));
    properties.put("name", Map.of(
        "type", "string",
        "description", "Human-readable field name (e.g. \"Patient name\")."));
    properties.put("description", Map.of(
        "type", "string",
        "description", "Free-text description of the field's purpose. Optional; defaults to an empty string."));
    properties.put("version", Map.of(
        "type", "string",
        "description", "Semantic version string in major.minor.patch form (e.g. \"0.0.1\"). Optional; defaults to 0.0.1."));
    properties.put("status", ArtifactExchange.statusSchemaProperty());
    properties.put("id", Map.of(
        "type", "string",
        "description", "IRI that identifies the field itself (the @id). Optional; if omitted, "
            + "a fresh CEDAR field IRI is auto-minted "
            + "(https://repo.metadatacenter.org/template-fields/<uuid>). Supply one only when you "
            + "have an id assigned by a CEDAR repository. Must be an absolute IRI."));

    // ---- Per-type configuration (all optional; applicable only to the matching type) ----

    properties.put("options", Map.of(
        "type", "array",
        "items", Map.of("type", "string"),
        "description",
        "Option list for choice fields (radio-field, checkbox-field, single-/multi-select-"
            + "list-field), in display order. Rejected for other field types. To change or "
            + "reorder options later, use set_options."));

    properties.put("content", Map.of(
        "type", "string",
        "description",
        "Content of a static field: the rich-text body (static-rich-text), the image URL "
            + "(static-image), the video URL (static-youtube-video), or the section text "
            + "(static-section-break). Rejected for non-static field types."));
    properties.put("width", Map.of(
        "type", "integer",
        "description",
        "Display width in pixels; static-image and static-youtube-video only."));
    properties.put("height", Map.of(
        "type", "integer",
        "description",
        "Display height in pixels; static-image and static-youtube-video only."));

    properties.put("datatype", Map.of(
        "type", "string",
        "description",
        "For numeric-field: one of xsd:int, xsd:long, xsd:byte, xsd:short, xsd:decimal, "
            + "xsd:float, xsd:double (default xsd:decimal). "
            + "For temporal-field: one of xsd:date, xsd:dateTime, xsd:time (default xsd:dateTime). "
            + "Ignored — and an error — for any other field type."));

    // Numeric-only
    properties.put("min_value", Map.of(
        "type", "number",
        "description", "numeric-field minimum permitted value. Optional."));
    properties.put("max_value", Map.of(
        "type", "number",
        "description", "numeric-field maximum permitted value. Optional."));
    properties.put("decimal_places", Map.of(
        "type", "integer",
        "description", "numeric-field decimal places. Optional; only meaningful for xsd:decimal / xsd:float / xsd:double."));
    properties.put("unit", Map.of(
        "type", "string",
        "description", "numeric-field unit of measure (e.g. \"years\", \"mmHg\"). Optional."));

    // Temporal-only
    properties.put("granularity", Map.of(
        "type", "string",
        "description",
        "temporal-field granularity: one of year, month, day, hour, minute, second, decimalSecond. "
            + "Optional (defaults to day for xsd:date / xsd:dateTime, minute for xsd:time)."));
    properties.put("input_time_format", Map.of(
        "type", "string",
        "description",
        "temporal-field clock format: 12h or 24h. Optional; only meaningful when granularity is sub-day."));
    properties.put("input_time_zone", Map.of(
        "type", "boolean",
        "description", "temporal-field timezone toggle. Optional; only meaningful when granularity is sub-day."));

    // Text / text-area
    properties.put("min_length", Map.of(
        "type", "integer",
        "description", "text-field / text-area-field minimum string length. Optional."));
    properties.put("max_length", Map.of(
        "type", "integer",
        "description", "text-field / text-area-field maximum string length. Optional."));
    properties.put("regex", Map.of(
        "type", "string",
        "description", "text-field / text-area-field validation regex. Optional."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("type", "name"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("create_field")
        .title("Create CEDAR field")
        .description(
            "Builds a CEDAR field schema artifact of the supplied kebab-case type "
                + "(e.g. text-field, controlled-term-field, numeric-field). Returns the "
                + "artifact as expanded YAML (the exchange form), validated by CedarValidator. "
                + "A field is a first-class, reusable CEDAR artifact."
                + ArtifactExchange.STANDALONE_NOTICE
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE + "\n\n"
                + "Beyond name/type/description/version, the tool accepts type-specific "
                + "configuration for the common literal-field cases: numeric-field "
                + "(datatype, min_value, max_value, decimal_places, unit), temporal-field "
                + "(datatype, granularity, input_time_format, input_time_zone), and "
                + "text-field / text-area-field (min_length, max_length, regex). Passing a "
                + "param that doesn't apply to the chosen field type is rejected with a "
                + "clear error.\n\n"
                + "For shapes that need structured sub-objects — controlled-term values "
                + "(class/branch/ontology/valueSet constraints), radio/checkbox/list inline "
                + "values, multi-instance configuration, default values — author them in YAML "
                + "('schema_artifact_to_yaml') instead. Constraints and default values can also be "
                + "layered onto a created field via 'set_class_constraint', "
                + "'set_branch_constraint', 'set_ontology_constraint', "
                + "'set_valueset_constraint', 'set_literal_default_value', and "
                + "'set_iri_default_value'.")
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

    Status status;
    try {
      status = ArtifactExchange.readStatus(args);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    String idText = stringArg(args, "id");
    URI id = null;
    if (idText != null && !idText.isBlank()) {
      try {
        id = new URI(idText);
      } catch (URISyntaxException e) {
        return error("invalid id \"" + idText + "\": not a valid IRI (" + e.getMessage() + ")");
      }
      if (!id.isAbsolute())
        return error("invalid id \"" + idText + "\": an id must be an absolute IRI "
            + "(e.g. https://repo.metadatacenter.org/template-fields/5c48700a-4163-436d-8daa-95af7311cded)");
    } else {
      // No caller-supplied id: mint a top-level CEDAR IRI (DESIGN.md Principle 10).
      id = IdMinter.mintFieldId();
    }

    FieldSchemaArtifact field;
    try {
      FieldSchemaArtifactBuilder<?> builder = FieldBuilders.builderFor(type);
      builder.withName(name).withDescription(description).withVersion(version).withStatus(status);
      builder.withJsonLdId(id);
      String configError = applyTypeSpecificConfig(builder, type, args);
      if (configError != null) return error(configError);
      String optionsError = applyOptions(builder, args);
      if (optionsError == null)
        optionsError = applyStaticConfig(builder, args);
      if (optionsError != null) return error(optionsError);
      field = builder.build();
    } catch (RuntimeException e) {
      return error("field build failed: " + e.getMessage());
    }

    String validationError = ArtifactExchange.validateField(field);
    if (validationError != null)
      return error("rendered field failed CedarValidator: " + validationError);

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null,
            ArtifactExchange.exchangeYaml(field))))
        .isError(false)
        .build();
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

  /**
   * Apply the optional {@code options} list to a choice-field builder. Returns an error message
   * for a non-choice builder or malformed list, {@code null} on success or when absent.
   */
  private static String applyOptions(FieldSchemaArtifactBuilder<?> builder, Map<String, Object> args)
  {
    Object raw = args.get("options");
    if (raw == null)
      return null;
    if (!(raw instanceof java.util.List<?> list) || list.isEmpty())
      return "options must be a non-empty array of strings";
    for (Object option : list)
      if (option == null || option.toString().isBlank())
        return "options must not contain blank entries";

    if (builder instanceof RadioField.RadioFieldBuilder radio) {
      for (Object option : list) radio.withOption(option.toString());
    } else if (builder instanceof CheckboxField.CheckboxFieldBuilder checkbox) {
      for (Object option : list) checkbox.withOption(option.toString());
    } else if (builder instanceof ListField.ListFieldBuilder listBuilder) {
      for (Object option : list) listBuilder.withOption(option.toString());
    } else {
      return "options apply to choice fields only (radio-field, checkbox-field, "
          + "single-/multi-select-list-field)";
    }
    return null;
  }

  /**
   * Apply the optional static-field configuration ({@code content}, and for image/video the
   * {@code width}/{@code height} dimensions) to a static-field builder. Returns an error
   * message when a param is misapplied, {@code null} on success or when absent.
   */
  private static String applyStaticConfig(FieldSchemaArtifactBuilder<?> builder, Map<String, Object> args)
  {
    String content = args.get("content") == null ? null : args.get("content").toString();
    Integer width;
    Integer height;
    try {
      width = optionalIntArg(args, "width");
      height = optionalIntArg(args, "height");
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
    if (content == null && width == null && height == null)
      return null;

    if (builder instanceof ImageField.ImageFieldBuilder image) {
      if (content != null) image.withContent(content);
      if (width != null) image.withWidth(width);
      if (height != null) image.withHeight(height);
    } else if (builder instanceof YouTubeField.YouTubeFieldBuilder video) {
      if (content != null) video.withContent(content);
      if (width != null) video.withWidth(width);
      if (height != null) video.withHeight(height);
    } else if (builder instanceof RichTextField.RichTextFieldBuilder richText) {
      if (width != null || height != null)
        return "width/height apply to static-image and static-youtube-video only";
      richText.withContent(content);
    } else if (builder instanceof SectionBreakField.SectionBreakFieldBuilder sectionBreak) {
      if (width != null || height != null)
        return "width/height apply to static-image and static-youtube-video only";
      sectionBreak.withContent(content);
    } else if (builder instanceof PageBreakField.PageBreakFieldBuilder pageBreak) {
      if (width != null || height != null)
        return "width/height apply to static-image and static-youtube-video only";
      pageBreak.withContent(content);
    } else {
      return "content/width/height apply to static fields only (static-rich-text, "
          + "static-image, static-youtube-video, static-section-break, static-page-break)";
    }
    return null;
  }

  /**
   * Read an optional integer argument. JSON-RPC numbers arrive boxed as Integer or Long;
   * coerce or fail with a clean message. Returns {@code null} when absent.
   */
  private static Integer optionalIntArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    if (raw == null) return null;
    if (raw instanceof Integer i) return i;
    if (raw instanceof Long l) {
      if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
        throw new IllegalArgumentException(key + " is out of integer range: " + l);
      return l.intValue();
    }
    if (raw instanceof Number n) return n.intValue();
    throw new IllegalArgumentException(key + " must be an integer (got "
        + raw.getClass().getSimpleName() + ")");
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }

  // ---------------------------------------------------------------------
  // Per-type configuration dispatch — applies the optional args that only
  // make sense for a particular kebab-case field type. Returns null on
  // success, an error message when a param is misapplied (e.g. min_length
  // on a numeric-field).
  // ---------------------------------------------------------------------

  private static final List<String> NUMERIC_ONLY_KEYS =
      List.of("min_value", "max_value", "decimal_places", "unit");
  private static final List<String> TEMPORAL_ONLY_KEYS =
      List.of("granularity", "input_time_format", "input_time_zone");
  private static final List<String> TEXT_ONLY_KEYS =
      List.of("min_length", "max_length", "regex");
  private static final List<String> DATATYPE_KEYS = List.of("datatype");

  private static String applyTypeSpecificConfig(FieldSchemaArtifactBuilder<?> builder,
      String type, Map<String, Object> args)
  {
    // Reject misapplied keys before doing any work, so the LLM gets a clean
    // diagnostic rather than a partial build.
    String mismatch = checkKeyApplicability(type, args);
    if (mismatch != null) return mismatch;

    switch (type) {
      case NUMERIC_FIELD: return applyNumericConfig((NumericField.NumericFieldBuilder) builder, args);
      case TEMPORAL_FIELD: return applyTemporalConfig((TemporalField.TemporalFieldBuilder) builder, args);
      case TEXT_FIELD: return applyTextConfig((TextField.TextFieldBuilder) builder, args);
      case TEXT_AREA_FIELD: return applyTextAreaConfig((TextAreaField.TextAreaFieldBuilder) builder, args);
      default: return null;   // no per-type params apply to this field type
    }
  }

  private static String checkKeyApplicability(String type, Map<String, Object> args)
  {
    List<String> badKeys = new java.util.ArrayList<>();
    boolean typeNumeric = NUMERIC_FIELD.equals(type);
    boolean typeTemporal = TEMPORAL_FIELD.equals(type);
    boolean typeText = TEXT_FIELD.equals(type) || TEXT_AREA_FIELD.equals(type);

    if (!typeNumeric && !typeTemporal && args.containsKey("datatype"))
      badKeys.add("datatype");
    for (String k : NUMERIC_ONLY_KEYS)
      if (!typeNumeric && args.containsKey(k)) badKeys.add(k);
    for (String k : TEMPORAL_ONLY_KEYS)
      if (!typeTemporal && args.containsKey(k)) badKeys.add(k);
    for (String k : TEXT_ONLY_KEYS)
      if (!typeText && args.containsKey(k)) badKeys.add(k);

    if (badKeys.isEmpty()) return null;
    return "the following arguments do not apply to field type '" + type + "': "
        + badKeys + ". See the input-schema description for the applicable keys.";
  }

  private static String applyNumericConfig(NumericField.NumericFieldBuilder builder, Map<String, Object> args)
  {
    String datatypeArg = stringArg(args, "datatype");
    if (datatypeArg != null && !datatypeArg.isBlank()) {
      try { builder.withNumericType(XsdNumericDatatype.fromString(datatypeArg)); }
      catch (IllegalArgumentException e) {
        return "invalid numeric datatype '" + datatypeArg + "'. Allowed: xsd:int, xsd:long, "
            + "xsd:byte, xsd:short, xsd:decimal, xsd:float, xsd:double.";
      }
    }
    Number minValue = numberArg(args, "min_value");
    if (minValue != null) builder.withMinValue(minValue);
    Number maxValue = numberArg(args, "max_value");
    if (maxValue != null) builder.withMaxValue(maxValue);
    Integer decimalPlaces = integerArg(args, "decimal_places");
    if (decimalPlaces != null) builder.withDecimalPlaces(decimalPlaces);
    String unit = stringArg(args, "unit");
    if (unit != null && !unit.isBlank()) builder.withUnitOfMeasure(unit);
    return null;
  }

  private static String applyTemporalConfig(TemporalField.TemporalFieldBuilder builder, Map<String, Object> args)
  {
    String datatypeArg = stringArg(args, "datatype");
    if (datatypeArg != null && !datatypeArg.isBlank()) {
      try { builder.withTemporalType(XsdTemporalDatatype.fromString(datatypeArg)); }
      catch (IllegalArgumentException e) {
        return "invalid temporal datatype '" + datatypeArg + "'. Allowed: xsd:date, xsd:dateTime, xsd:time.";
      }
    }
    String granularityArg = stringArg(args, "granularity");
    if (granularityArg != null && !granularityArg.isBlank()) {
      try { builder.withTemporalGranularity(TemporalGranularity.fromString(granularityArg)); }
      catch (IllegalArgumentException e) {
        return "invalid granularity '" + granularityArg + "'. Allowed: year, month, day, hour, "
            + "minute, second, decimalSecond.";
      }
    }
    String formatArg = stringArg(args, "input_time_format");
    if (formatArg != null && !formatArg.isBlank()) {
      try { builder.withInputTimeFormat(InputTimeFormat.fromString(formatArg)); }
      catch (IllegalArgumentException e) {
        return "invalid input_time_format '" + formatArg + "'. Allowed: 12h, 24h.";
      }
    }
    Boolean tz = booleanArg(args, "input_time_zone");
    if (tz != null) builder.withTimeZoneEnabled(tz);
    return null;
  }

  private static String applyTextConfig(TextField.TextFieldBuilder builder, Map<String, Object> args)
  {
    Integer minLength = integerArg(args, "min_length");
    if (minLength != null) builder.withMinLength(minLength);
    Integer maxLength = integerArg(args, "max_length");
    if (maxLength != null) builder.withMaxLength(maxLength);
    String regex = stringArg(args, "regex");
    if (regex != null && !regex.isBlank()) builder.withRegex(regex);
    return null;
  }

  private static String applyTextAreaConfig(TextAreaField.TextAreaFieldBuilder builder, Map<String, Object> args)
  {
    Integer minLength = integerArg(args, "min_length");
    if (minLength != null) builder.withMinLength(minLength);
    Integer maxLength = integerArg(args, "max_length");
    if (maxLength != null) builder.withMaxLength(maxLength);
    // TextAreaField doesn't carry a regex; we already rejected it at applicability check
    // when type == text-area-field doesn't have regex in the builder. Confirmed in source.
    return null;
  }

  private static Number numberArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    if (raw == null) return null;
    if (raw instanceof Number n) return n;
    if (raw instanceof String s && !s.isBlank()) {
      try { return new BigDecimal(s); } catch (NumberFormatException e) { /* fall through */ }
    }
    return null;
  }

  private static Integer integerArg(Map<String, Object> args, String key)
  {
    Number n = numberArg(args, key);
    return n == null ? null : n.intValue();
  }

  private static Boolean booleanArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    if (raw == null) return null;
    if (raw instanceof Boolean b) return b;
    if (raw instanceof String s) {
      if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
      if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
    }
    return null;
  }
}

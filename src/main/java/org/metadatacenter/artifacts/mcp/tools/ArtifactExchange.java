package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared artifact I/O for the threading tools. CEDAR artifacts move between tool calls as
 * <strong>expanded YAML</strong> — the compact, lossless exchange currency (DESIGN.md
 * Principle 8). This helper centralizes the two operations every threading tool needs:
 *
 * <ul>
 *   <li><strong>read</strong> an incoming artifact from YAML into the in-memory model
 *       (the canonical representation; the wire format is just transport), and</li>
 *   <li><strong>render</strong> an outgoing model back to expanded YAML.</li>
 * </ul>
 *
 * <p>The reader runs in compact mode ({@code new YamlArtifactReader(true)}) so it accepts
 * both the compact authoring form and the expanded exchange form the tools emit — the only
 * difference between them is the presence of provenance keys, which the compact reader
 * tolerates either way.
 *
 * <p>JSON Schema is no longer an exchange format between tools; it is produced only by the
 * dedicated {@code *_to_json} export tools and consumed only by the {@code *_to_yaml} import
 * tools. Validation (DESIGN.md Principle 6) still renders JSON internally and runs
 * {@link CedarValidator} — see the {@code validate*} helpers — because the validator's
 * contract is with the JSON Schema serialization.
 */
final class ArtifactExchange
{
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader JSON_READER = new JsonArtifactReader();
  // Compact-mode reader: accepts both compact and expanded YAML (modelVersion absent is
  // defaulted; present-but-wrong is still rejected).
  private static final YamlArtifactReader YAML_READER = new YamlArtifactReader(true);

  private ArtifactExchange() {}

  /**
   * Display directive appended to every artifact-returning tool description. The consuming
   * LLM only sees the tool surface (DESIGN.md Principle 4), so the instruction not to mangle
   * the result when relaying it to the user has to live here. The {@code @id} lines are the
   * artifact's identity and are the most common casualty of "summarize for brevity".
   */
  static final String VERBATIM_NOTICE =
      " When relaying this result to the user, reproduce the returned YAML verbatim: never drop "
          + "or summarize the 'id:' (@id) lines or any other field, and do not replace the YAML "
          + "with a table that omits content.";

  /**
   * Extra directive for the {@code create_*} builders that return a standalone, reusable
   * artifact. Curbs the over-eager pattern of silently grafting a freshly created field or
   * element onto a template the user did not ask to modify.
   */
  static final String STANDALONE_NOTICE =
      " The artifact is returned standalone — it is NOT added to any template or element. "
          + "Attach it to a parent only via add_field / add_element, and only when the user "
          + "explicitly asks for that step.";

  /**
   * Input directive for tools that accept an artifact the caller obtained elsewhere (e.g. a file
   * from the wild). Reassures the LLM that supplying a large artifact inline is expected — there
   * is no size concern and no file-path option — and, like {@link #VERBATIM_NOTICE} on the output
   * side, insists the serialization be passed through untouched. Massaging it (reformatting,
   * re-indenting, re-serializing, "fixing") would mean validating/converting a rewritten copy
   * rather than the artifact in hand.
   */
  static final String VERBATIM_INPUT_NOTICE =
      " Supply the artifact exactly as you obtained it: read its file and paste the content inline "
          + "(a large artifact inline is fine — there is no size limit to worry about and no "
          + "file-path parameter). Do not reformat, re-indent, re-serialize, or otherwise massage "
          + "the content — pass the bytes verbatim, so the operation sees the real artifact and not "
          + "an LLM-rewritten copy.";

  // ---------------------------------------------------------------------
  // serialized artifact -> model (incoming artifacts)
  //
  // The exchange form is expanded YAML, but a JSON Schema artifact (e.g. one produced by a
  // *_to_json export tool, or fetched from cedar-server) is also accepted: the format is
  // auto-detected so callers never have to convert before threading. Both serializations
  // resolve to the same in-memory model — the canonical representation.
  // ---------------------------------------------------------------------

  static TemplateSchemaArtifact readTemplate(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readTemplateSchemaArtifact(asObjectNode(text))
        : YAML_READER.readTemplateSchemaArtifact(parseYamlMap(text));
  }

  static ElementSchemaArtifact readElement(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readElementSchemaArtifact(asObjectNode(text))
        : YAML_READER.readElementSchemaArtifact(parseYamlMap(text));
  }

  static FieldSchemaArtifact readField(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readFieldSchemaArtifact(asObjectNode(text))
        : YAML_READER.readFieldSchemaArtifact(parseYamlMap(text));
  }

  static TemplateInstanceArtifact readInstance(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readTemplateInstanceArtifact(asObjectNode(text))
        : YAML_READER.readTemplateInstanceArtifact(parseYamlMap(text));
  }

  /**
   * A SnakeYAML parser that does NOT auto-resolve date-like scalars to {@code java.util.Date}.
   * CEDAR temporal values and defaults (e.g. {@code 2026-01-01}) are lexical strings; letting
   * YAML coerce them to Date breaks the round trip (the reader expects strings). Every other
   * implicit type (bool/int/float/null/merge) resolves as usual.
   */
  private static Yaml newYaml()
  {
    LoaderOptions loaderOptions = new LoaderOptions();
    DumperOptions dumperOptions = new DumperOptions();
    return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions),
      dumperOptions, loaderOptions, new NoTimestampResolver());
  }

  private static final class NoTimestampResolver extends Resolver
  {
    @Override protected void addImplicitResolvers()
    {
      addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
      addImplicitResolver(Tag.INT, INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, MERGE, "<");
      addImplicitResolver(Tag.NULL, NULL, "~nN\0");
      addImplicitResolver(Tag.NULL, EMPTY, null);
      // Tag.TIMESTAMP intentionally not registered — keep date-like scalars as strings.
    }
  }

  /** A serialized artifact is JSON when its first non-whitespace character is '{'. */
  private static boolean looksLikeJson(String text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!Character.isWhitespace(c))
        return c == '{';
    }
    return false;
  }

  private static ObjectNode asObjectNode(String json)
  {
    JsonNode node;
    try {
      node = JACKSON2.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("artifact JSON parse failed: " + e.getMessage());
    }
    if (!(node instanceof ObjectNode objectNode))
      throw new IllegalArgumentException("artifact JSON must parse to an object");
    return objectNode;
  }

  /**
   * Parse a YAML document into the {@code LinkedHashMap} the reader expects. SnakeYAML throws
   * a {@link RuntimeException} for malformed input; a non-mapping top level is rejected with a
   * clear message rather than a class-cast surprise downstream.
   */
  static LinkedHashMap<String, Object> parseYamlMap(String yamlText)
  {
    Object parsed = newYaml().load(yamlText);
    if (!(parsed instanceof Map<?, ?>))
      throw new IllegalArgumentException("YAML must parse to a mapping at the top level (got "
          + (parsed == null ? "null" : parsed.getClass().getSimpleName()) + ")");
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet())
      map.put(String.valueOf(entry.getKey()), entry.getValue());
    return map;
  }

  // ---------------------------------------------------------------------
  // model -> YAML (outgoing artifacts)
  // ---------------------------------------------------------------------

  /**
   * Render any artifact as YAML. {@code isCompact} true (the default the tools use) is the lean
   * form — provenance, status, version, and modelVersion omitted; the canonical exchange/display
   * form. {@code isCompact} false is the expanded, lossless form for persistence.
   */
  static String toYaml(Artifact artifact, boolean isCompact)
  {
    return YamlSerializer.getYAML(artifact, isCompact, false);
  }

  /**
   * Read the optional {@code isCompact} tool argument. Defaults to {@code true} (compact) — the
   * lean form is what callers want by default; expanded is requested explicitly for persistence.
   * A non-boolean value is treated as the default rather than failing the call.
   */
  static boolean readIsCompact(Map<String, Object> args)
  {
    return readIsCompact(args, true);
  }

  /** Read the optional {@code isCompact} argument, falling back to {@code defaultCompact}. */
  static boolean readIsCompact(Map<String, Object> args, boolean defaultCompact)
  {
    Object raw = args.get("isCompact");
    return raw instanceof Boolean b ? b : defaultCompact;
  }

  /**
   * The shared {@code isCompact} input-schema property for schema artifacts (template/element/
   * field), which default to compact: only provenance is dropped, the structure is intact.
   */
  static Map<String, Object> isCompactSchemaProperty()
  {
    return Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to return the lean compact YAML (default true) — the form for threading into "
            + "follow-up tools and for display. Pass false for the expanded, lossless form "
            + "(carrying provenance, status, version, modelVersion) intended for persistence.");
  }

  /**
   * The {@code isCompact} property for instance-returning tools, which default to EXPANDED. A
   * skeleton or partially-filled instance carries value-less field slots that compact YAML
   * elides; those slots are structural (set_field_value needs them), so threading instances
   * uses the expanded form. Pass true only to display a finished instance leanly.
   */
  static Map<String, Object> isCompactInstanceSchemaProperty()
  {
    return Map.of(
        "type", "boolean",
        "default", Boolean.FALSE,
        "description",
        "Whether to return compact YAML. Defaults to false (expanded) for instances: compact "
            + "elides value-less field slots, which are structural and needed to keep filling "
            + "the instance via set_field_value. Pass true to display a finished instance leanly.");
  }

  // ---------------------------------------------------------------------
  // Bridges for tools whose internal logic operates on a JSON ObjectNode.
  // These let a handler keep its existing JSON-based body and change only the
  // incoming-parse and outgoing-serialize lines: read accepts YAML or JSON and
  // hands back an ObjectNode; render takes the validated ObjectNode and emits
  // expanded YAML. JSON stays a private intermediate; the wire form is YAML.
  // ---------------------------------------------------------------------

  /**
   * Parse an incoming artifact (YAML or JSON) to a JSON {@code ObjectNode}. A YAML document is
   * read into the model and re-rendered to JSON so downstream JSON-Schema logic is unaffected.
   */
  static ObjectNode toObjectNode(String text)
  {
    if (looksLikeJson(text))
      return asObjectNode(text);

    LinkedHashMap<String, Object> map = parseYamlMap(text);
    String type = map.get("type") == null ? "" : String.valueOf(map.get("type"));
    return switch (type) {
      case "template" -> JSON_RENDERER.renderTemplateSchemaArtifact(YAML_READER.readTemplateSchemaArtifact(map));
      case "element" -> JSON_RENDERER.renderElementSchemaArtifact(YAML_READER.readElementSchemaArtifact(map));
      case "instance" -> JSON_RENDERER.renderTemplateInstanceArtifact(YAML_READER.readTemplateInstanceArtifact(map));
      // Every other top-level type discriminator is a field kind (text-field, numeric-field,
      // controlled-term-field, the ext-* and static-* families, ...).
      default -> JSON_RENDERER.renderFieldSchemaArtifact(YAML_READER.readFieldSchemaArtifact(map));
    };
  }

  /** Render a CEDAR JSON-Schema {@code ObjectNode} (template, element, field, or instance) as YAML. */
  static String jsonNodeToYaml(ObjectNode node, boolean isCompact)
  {
    JsonNode typeNode = node.get("@type");
    String type = typeNode != null && typeNode.isTextual() ? typeNode.asText() : "";
    Artifact artifact;
    if (type.contains("Element"))
      artifact = JSON_READER.readElementSchemaArtifact(node);
    else if (type.contains("Field"))
      artifact = JSON_READER.readFieldSchemaArtifact(node);
    else if (type.contains("Template"))
      artifact = JSON_READER.readTemplateSchemaArtifact(node);
    else if (node.has("schema:isBasedOn"))
      artifact = JSON_READER.readTemplateInstanceArtifact(node);
    else
      throw new IllegalArgumentException("cannot determine artifact kind from JSON @type \"" + type + "\"");
    return toYaml(artifact, isCompact);
  }

  // ---------------------------------------------------------------------
  // validation (DESIGN.md Principle 6) — render JSON, run CedarValidator
  // ---------------------------------------------------------------------

  /** Returns {@code null} when the template is valid, otherwise a formatted error string. */
  static String validateTemplate(TemplateSchemaArtifact template)
  {
    try {
      return report(VALIDATOR.validateTemplate(JSON_RENDERER.renderTemplateSchemaArtifact(template)));
    } catch (Exception e) {
      return "CedarValidator threw while validating template: " + e.getMessage();
    }
  }

  static String validateElement(ElementSchemaArtifact element)
  {
    try {
      return report(VALIDATOR.validateTemplateElement(JSON_RENDERER.renderElementSchemaArtifact(element)));
    } catch (Exception e) {
      return "CedarValidator threw while validating element: " + e.getMessage();
    }
  }

  static String validateField(FieldSchemaArtifact field)
  {
    try {
      return report(VALIDATOR.validateTemplateField(JSON_RENDERER.renderFieldSchemaArtifact(field)));
    } catch (Exception e) {
      return "CedarValidator threw while validating field: " + e.getMessage();
    }
  }

  private static String report(ValidationReport report)
  {
    return "true".equals(report.getValidationStatus()) ? null : formatErrors(report);
  }

  static String formatErrors(ValidationReport report)
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

  /**
   * Render a {@link ValidationReport} as the public {@code {"valid": ...}} report the
   * {@code validate_*} tools return: {@code {"valid": true}} on success, otherwise
   * {@code {"valid": false, "errors": [...]}} with the validator's diagnostics. The verdict is
   * data, not a tool error (DESIGN.md Principle 5), so an invalid artifact still yields a report.
   */
  static String validationReportJson(ValidationReport report)
  {
    boolean valid = "true".equals(report.getValidationStatus());
    LinkedHashMap<String, Object> result = new LinkedHashMap<>();
    result.put("valid", valid);
    if (!valid) {
      List<String> errors = new ArrayList<>();
      for (ErrorItem err : report.getErrors())
        errors.add(err.toString());
      result.put("errors", errors);
    }
    try {
      return JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("failed to serialize validation report: " + e.getMessage(), e);
    }
  }
}

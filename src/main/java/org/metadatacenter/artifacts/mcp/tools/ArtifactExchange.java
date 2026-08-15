package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.core.Artifact;
import org.metadatacenter.artifacts.model.core.Status;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
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
 * <strong>expanded YAML</strong> — the lossless exchange currency (DESIGN.md
 * Principle 8). This helper centralizes the two operations every threading tool needs:
 *
 * <ul>
 *   <li><strong>read</strong> an incoming artifact from YAML into the in-memory model
 *       (the canonical representation; the wire format is just transport), and</li>
 *   <li><strong>render</strong> an outgoing model back to expanded YAML.</li>
 * </ul>
 *
 * <p>Two readers, chosen by what the document looks like. The compact form describes an artifact
 * being authored: it names neither the artifact nor what a repository records about it, and its
 * reader refuses a document that carries an identifier. The expanded form the tools emit between
 * calls does carry one — identity is what has to survive from one stateless call to the next — and
 * its reader wants the model version the compact form omits. {@link #readerFor} picks by inspecting
 * the document, so an author may hand in either.
 *
 * <p>JSON Schema is no longer an exchange format between tools; it is produced only by the
 * render tools ({@code render_schema_artifact} / {@code render_instance_artifact} with
 * {@code format: json}) and consumed only by the same tools when importing a JSON Schema back to
 * YAML. Validation (DESIGN.md Principle 6) still renders JSON internally and runs
 * {@link CedarValidator} — see the {@code validate*} helpers — because the validator's
 * contract is with the JSON Schema serialization.
 */
final class ArtifactExchange
{
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader JSON_READER = new JsonArtifactReader();
  private static final YamlArtifactReader COMPACT_YAML_READER = new YamlArtifactReader(true);
  private static final YamlArtifactReader EXPANDED_YAML_READER = new YamlArtifactReader(false);

  /**
   * The reader the document asks for.
   *
   * A document naming the artifact it describes, or stating a model version, is the expanded form the
   * tools exchange; anything else is the compact form an author writes. Choosing wrongly is not a
   * matter of tolerance: the compact reader refuses an identifier, and the expanded reader requires a
   * model version.
   */
  private static YamlArtifactReader readerFor(LinkedHashMap<String, Object> document)
  {
    return document.containsKey("modelVersion") ? EXPANDED_YAML_READER : COMPACT_YAML_READER;
  }

  static TemplateSchemaArtifact readTemplateSchemaYaml(LinkedHashMap<String, Object> map)
  {
    return readerFor(map).readTemplateSchemaArtifact(map);
  }

  static ElementSchemaArtifact readElementSchemaYaml(LinkedHashMap<String, Object> map)
  {
    return readerFor(map).readElementSchemaArtifact(map);
  }

  static FieldSchemaArtifact readFieldSchemaYaml(LinkedHashMap<String, Object> map)
  {
    return readerFor(map).readFieldSchemaArtifact(map);
  }

  /**
   * An instance carries no model version in either form, so a document cannot say which form it is in.
   * The tools exchange instances that name themselves — identity is what has to survive between two
   * stateless calls — so instances are read with the reader that accepts an identifier. An author
   * writing one by hand simply leaves it out.
   */
  static TemplateInstanceArtifact readTemplateInstanceYaml(LinkedHashMap<String, Object> map)
  {
    return EXPANDED_YAML_READER.readTemplateInstanceArtifact(map);
  }

  static ElementInstanceArtifact readElementInstanceYaml(LinkedHashMap<String, Object> map)
  {
    return EXPANDED_YAML_READER.readElementInstanceArtifact(map);
  }

  private ArtifactExchange() {}

  /**
   * Display directive appended to every artifact-returning tool description. The consuming
   * LLM only sees the tool surface (DESIGN.md Principle 4), so the instruction not to mangle
   * the result when relaying it to the user has to live here. The {@code @id} lines are the
   * artifact's identity and are the most common casualty of "summarize for brevity".
   */
  static final String VERBATIM_NOTICE =
      " Whatever YAML you show the user — this result or a render_schema_artifact / "
          + "render_instance_artifact rendering of it — show it "
          + "verbatim: never hand-edit, summarize, or reformat it, never drop the 'id:' (@id) "
          + "lines or any other field, and do not replace the YAML with a table that omits "
          + "content.";

  /**
   * Display directive appended to every mutating tool description (after
   * {@link #VERBATIM_NOTICE}). The exchange form is expanded and therefore verbose; in
   * interactive sessions the lean compact view is the better thing to put in front of a person —
   * but only as a display, produced by a rendering tool, never as the artifact that threads
   * onward (compaction drops provenance).
   */
  static final String DISPLAY_NOTICE =
      " This result is the expanded exchange form. When showing the artifact to the user in an "
          + "interactive session, prefer the lean view — call the matching render tool "
          + "(render_schema_artifact / render_instance_artifact) with compact: true and display "
          + "its output instead. But ALWAYS pass THIS returned YAML "
          + "into subsequent tool calls — the compacted display view drops provenance (version, "
          + "status) and must never be threaded onward.";

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
      " Supply the artifact exactly as you obtained it. Read its file and paste the content inline "
          + "(a large artifact inline is fine — there is no size limit to worry about and no "
          + "file-path parameter). Do not reformat, re-indent, re-serialize, or otherwise massage "
          + "the content — pass the bytes verbatim, so the operation sees the real artifact and not "
          + "an LLM-rewritten copy.";

  // ---------------------------------------------------------------------
  // serialized artifact -> model (incoming artifacts)
  //
  // The exchange form is expanded YAML, but a JSON Schema artifact (e.g. one produced by a
  // render tool with format: json, or fetched from a CEDAR server) is also accepted — the format is
  // auto-detected so callers never have to convert before threading. Both serializations
  // resolve to the same in-memory model — the canonical representation.
  // ---------------------------------------------------------------------

  static TemplateSchemaArtifact readTemplate(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readTemplateSchemaArtifact(asObjectNode(text))
        : readTemplateSchemaYaml(parseYamlMap(text));
  }

  static ElementSchemaArtifact readElement(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readElementSchemaArtifact(asObjectNode(text))
        : readElementSchemaYaml(parseYamlMap(text));
  }

  static FieldSchemaArtifact readField(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readFieldSchemaArtifact(asObjectNode(text))
        : readFieldSchemaYaml(parseYamlMap(text));
  }

  static TemplateInstanceArtifact readInstance(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readTemplateInstanceArtifact(asObjectNode(text))
        : readTemplateInstanceYaml(parseYamlMap(text));
  }

  static ElementInstanceArtifact readElementInstance(String text)
  {
    return looksLikeJson(text)
        ? JSON_READER.readElementInstanceArtifact(asObjectNode(text))
        : readElementInstanceYaml(parseYamlMap(text));
  }

  /**
   * Distinguishes the two instance kinds (YAML or JSON) for the auto-detecting
   * {@code render_instance_artifact} tool. A YAML document
   * is keyed on its {@code type:} discriminator ({@code element-instance} vs {@code instance}); a
   * JSON document has no such discriminator, so a template instance is recognized by its
   * {@code schema:isBasedOn} (which an element instance lacks) and everything else is taken to be
   * an element instance. Returns {@code true} for an element instance.
   */
  static boolean isElementInstance(String text)
  {
    if (looksLikeJson(text)) {
      try {
        return !asObjectNode(text).has("schema:isBasedOn");
      } catch (RuntimeException malformed) {
        return false;
      }
    }
    return "element-instance".equals(String.valueOf(parseYamlMap(text).get("type")));
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
   * Render any artifact as YAML. {@code isCompact} true is the lean display form — provenance,
   * status, version, and modelVersion omitted; {@code isCompact} false is the expanded, lossless
   * form. The flag is a rendering choice and is exposed only by the render tools
   * ({@code render_schema_artifact} / {@code render_instance_artifact}); every mutating tool
   * returns {@link #exchangeYaml} unconditionally.
   */
  static String toYaml(Artifact artifact, boolean isCompact)
  {
    return YamlSerializer.getYAML(artifact, isCompact, false);
  }

  /**
   * The exchange form every mutating tool ({@code create_*}, {@code add_*}, {@code set_*},
   * {@code remove_child}) returns: expanded, lossless YAML. Always expanded so that nothing set
   * on an artifact — version, status, provenance, value-less instance slots — is ever silently
   * dropped between tool calls; the returned YAML is the artifact the next tool receives.
   * Compaction is a display choice, available via the render tools.
   */
  static String exchangeYaml(Artifact artifact)
  {
    return toYaml(artifact, false);
  }

  /** {@link #exchangeYaml(Artifact)} for handlers whose internal logic holds a JSON node. */
  static String exchangeYaml(ObjectNode node)
  {
    return jsonNodeToYaml(node, false);
  }

  /**
   * The shared {@code status} input-schema property for the schema-artifact {@code create_*}
   * tools, complementing {@code version}.
   */
  static Map<String, Object> statusSchemaProperty()
  {
    return Map.of(
        "type", "string",
        "enum", List.of("draft", "published"),
        "description",
        "Artifact status (bibo:status): \"draft\" or \"published\". Optional; defaults to draft.");
  }

  /**
   * Parse the optional {@code status} argument using the YAML vocabulary ({@code draft} /
   * {@code published}). Absent defaults to {@link Status#DRAFT}; anything else throws
   * {@link IllegalArgumentException} with a caller-facing message.
   */
  static Status readStatus(Map<String, Object> args)
  {
    Object raw = args.get("status");
    if (raw == null)
      return Status.DRAFT;
    return switch (raw.toString().trim().toLowerCase()) {
      case "", "draft" -> Status.DRAFT;
      case "published" -> Status.PUBLISHED;
      default -> throw new IllegalArgumentException(
          "invalid status \"" + raw + "\": must be \"draft\" or \"published\"");
    };
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
      case "template" -> JSON_RENDERER.renderTemplateSchemaArtifact(readTemplateSchemaYaml(map));
      case "element" -> JSON_RENDERER.renderElementSchemaArtifact(readElementSchemaYaml(map));
      case "instance" -> JSON_RENDERER.renderTemplateInstanceArtifact(readTemplateInstanceYaml(map));
      case "element-instance" -> JSON_RENDERER.renderElementInstanceArtifact(readElementInstanceYaml(map));
      // Every other top-level type discriminator is a field kind (text-field, numeric-field,
      // controlled-term-field, the ext-* and static-* families, ...).
      default -> JSON_RENDERER.renderFieldSchemaArtifact(readFieldSchemaYaml(map));
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

  /**
   * Render an artifact — supplied as YAML or JSON, any kind — to the requested serialization:
   * YAML (expanded unless {@code isCompact}) or pretty-printed JSON. The kind is auto-detected.
   * A parse failure throws (malformed input is rejected); no semantic validation is run, matching
   * the other rendering tools — validate with {@code validate_*} or rely on the server on upload.
   */
  static String renderArtifact(String text, boolean asYaml, boolean isCompact)
  {
    ObjectNode node = toObjectNode(text);
    if (!asYaml) {
      try {
        return JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(node);
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
      }
    }
    try {
      return jsonNodeToYaml(node, isCompact);
    } catch (IllegalArgumentException notSchemaOrTemplateInstance) {
      // Standalone element instance — no schema @type and no schema:isBasedOn for jsonNodeToYaml to
      // key on, so read it explicitly from the original text.
      return toYaml(readElementInstance(text), isCompact);
    }
  }

  /** Best-effort human label for an artifact's kind, for tool result summaries. */
  static String kindLabel(String text)
  {
    try {
      ArtifactKinds.Kind kind = ArtifactKinds.detect(toObjectNode(text));
      if (kind != null)
        return switch (kind) {
          case TEMPLATE -> "template";
          case ELEMENT -> "element";
          case FIELD -> "field";
          case INSTANCE -> "template instance";
        };
      return isElementInstance(text) ? "element instance" : "artifact";
    } catch (RuntimeException e) {
      return "artifact";
    }
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

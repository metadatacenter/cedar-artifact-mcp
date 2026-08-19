package org.metadatacenter.artifacts.mcp.tools;

/**
 * Canonical CEDAR YAML vocabulary documentation, baked into every {@code *_to_json}
 * tool's input-schema description so the calling LLM can author YAML correctly on the
 * first attempt rather than discovering keys by trial and error.
 *
 * <p>The MCP protocol gives the LLM only the input-schema description to work from —
 * anything not present here is invisible. DESIGN.md Principle 4 ("The tool surface is
 * the LLM's documentation") makes this a hard requirement, not a style preference.
 *
 * <p>Each constant below is a focused vocabulary block. Tools compose the blocks they
 * need: a {@code create_field} tool wants the field vocabulary; a
 * {@code render_schema_artifact} tool wants the full template+element+field set; a
 * {@code render_instance_artifact} tool wants the instance vocabulary instead.
 *
 * <p>When a new field type or value-constraint kind is added to the library, update the
 * relevant block here. The tests don't catch missing documentation — only the LLM does,
 * by getting stuck.
 */
final class YamlVocabulary
{
  private YamlVocabulary() {}

  // -----------------------------------------------------------------------
  // Universal keys on every template / element / field schema artifact.
  // -----------------------------------------------------------------------

  static final String COMMON_SCHEMA_KEYS = String.join("\n",
    "Common keys (apply to template, element, and field):",
    "  type            (required) kebab-case discriminator — see the field-type list below",
    "  name            (required) human-readable name",
    "  modelVersion    (required) always 1.6.0",
    "  description     optional   free text",
    "  version         optional   semantic version, e.g. 0.0.1",
    "  status          optional   draft | published",
    "  identifier      optional   schema.org identifier string",
    "  id              optional   absolute IRI for the artifact itself. Normally omitted: CEDAR",
    "                             mints every identifier, an artifact's own and its children's,",
    "                             when the artifact is created on a server. Supply one only to",
    "                             repeat an id already assigned, e.g.",
    "                             https://repo.metadatacenter.org/templates/5c48700a-4163-436d-8daa-95af7311cded");

  // -----------------------------------------------------------------------
  // Field-type vocabulary — what each kebab-case 'type:' means + its keys.
  // -----------------------------------------------------------------------

  static final String FIELD_TYPES_AND_KEYS = String.join("\n",
    "Field type discriminators (set under 'type:'):",
    "",
    "  text-field             plain text. Keys: minLength, maxLength, regex, default (string)",
    "  text-area-field        multi-line text. Same keys as text-field",
    "  numeric-field          number. Keys:",
    "                           datatype: one of xsd:int, xsd:long, xsd:byte, xsd:short,",
    "                                     xsd:decimal, xsd:float, xsd:double  (default xsd:decimal)",
    "                           minValue, maxValue, decimalPlaces, unit",
    "                           default: numeric literal. Bare numbers preferred for readability",
    "                                    (e.g. default: 42, default: 3.14). The library renders",
    "                                    plain integers and plain decimals unquoted, and reserves",
    "                                    quoting for forms YAML's auto-typing would otherwise change",
    "                                    on a subsequent read — leading zeros ('010' → 8 in octal),",
    "                                    exponential notation ('1e3' → 1000.0), or values that match",
    "                                    YAML reserved keywords. The library accepts either form on",
    "                                    read; the datatype line stays authoritative for the type.",
    "  temporal-field         date/time. Keys:",
    "                           datatype: one of xsd:date, xsd:dateTime, xsd:time  (default xsd:dateTime)",
    "                           granularity: year | month | day | hour | minute | second | decimalSecond",
    "                           inputTimeFormat: 12h | 24h   (only when granularity is sub-day)",
    "                           inputTimeZone: true | false",
    "  radio-field            single-select from inline literals. Required key:",
    "                           values: [{ label: \"Yes\" }, { label: \"No\" }, ...]",
    "  checkbox-field         multi-select from inline literals. Same shape as radio-field",
    "  single-select-list-field   dropdown, one selection. Same shape as radio-field",
    "  multi-select-list-field    dropdown, multiple selections. Same shape as radio-field",
    "  controlled-term-field  ontology-bound term. Keys:",
    "                           datatype: iri",
    "                           values: list of constraint entries — each entry has",
    "                             'type:' one of class | branch | ontology | valueSet",
    "                             plus the per-type fields below:",
    "                             'source*' names the vocabulary, 'term*' the term within it:",
    "                           - type: class      → sourceAcronym, termIri, termType, termLabel",
    "                           - type: branch     → sourceAcronym, sourceName, termBaseIri,",
    "                                                termBaseLabel, termMaxDepth?",
    "                           - type: ontology   → sourceAcronym, sourceName, termCount?",
    "                           - type: valueSet   → sourceAcronym, termBaseIri, termBaseLabel,",
    "                                                termCount?",
    "                             any entry may add sourceSystem, sourceIri and a version block",
    "  phone-number-field, email-field, link-field   no special keys",
    "  ext-ror-field, ext-orcid-field, ext-pfas-field, ext-rrid-field,",
    "  ext-pubmed-field, ext-nih-grant-id-field, ext-doi-field            no special keys",
    "  attribute-value-field  dynamic key-value pairs. No special keys",
    "  static-page-break, static-section-break       presentational. Keys: content",
    "  static-rich-text                              presentational. Keys: content",
    "  static-image, static-youtube-video            presentational. Keys: content, width, height");

  // -----------------------------------------------------------------------
  // Configuration sub-block — only present for fields/elements that are
  // children of a parent template or element.
  // -----------------------------------------------------------------------

  static final String CHILD_CONFIGURATION = String.join("\n",
    "Child 'configuration:' sub-block (only on nested fields/elements under 'children:'):",
    "  required, recommended       boolean   — value-constraint flags",
    "  hidden                      boolean   — UI hint",
    "  valueRecommendation         boolean   — UI hint for controlled-term",
    "  continuePreviousLine        boolean   — UI hint",
    "  multiple                    boolean   — wraps the field as multi-instance",
    "  minItems, maxItems          integer   — only when multiple is true",
    "  propertyIri                 string    — overrides the auto-generated @context IRI",
    "  overrideLabel, overrideDescription   strings — parent UI label overrides");

  // -----------------------------------------------------------------------
  // Children list — how to nest fields/elements under a template/element.
  // -----------------------------------------------------------------------

  static final String CHILDREN_BLOCK = String.join("\n",
    "Nesting under a template or element:",
    "  children:                    list of child fields/elements",
    "    - key: patient_name        REQUIRED unique key for the child within this parent",
    "      type: text-field         child's kebab-case type",
    "      name: Patient name       human-readable child name",
    "      ... (other type-specific keys)",
    "      configuration:           optional; see the configuration block above",
    "        required: true");

  // -----------------------------------------------------------------------
  // Concrete worked examples — three patterns that cover ~90% of authoring.
  // -----------------------------------------------------------------------

  static final String EXAMPLES = String.join("\n",
    "Example — numeric field with bounds, unit, and default:",
    "  type: numeric-field",
    "  name: Age",
    "  modelVersion: 1.6.0",
    "  datatype: xsd:int",
    "  minValue: 0",
    "  maxValue: 120",
    "  unit: years",
    "  default: 42             # bare number; renderer keeps unquoted for plain forms",
    "",
    "Example — controlled-term field bound to a class in DOID:",
    "  type: controlled-term-field",
    "  name: Primary diagnosis",
    "  modelVersion: 1.6.0",
    "  datatype: iri",
    "  values:",
    "    - type: class",
    "      sourceAcronym: DOID",
    "      termIri: http://purl.obolibrary.org/obo/DOID_4",
    "      termType: class",
    "      termLabel: disease",
    "",
    "Example — template with two children:",
    "  type: template",
    "  name: Patient intake",
    "  modelVersion: 1.6.0",
    "  version: 0.1.0",
    "  status: draft",
    "  children:",
    "    - key: patient_name",
    "      type: text-field",
    "      name: Patient name",
    "      configuration:",
    "        required: true",
    "    - key: age",
    "      type: numeric-field",
    "      name: Age",
    "      datatype: xsd:int");

  // -----------------------------------------------------------------------
  // Instance vocabulary — different shape: maps schema keys to values.
  // -----------------------------------------------------------------------

  static final String INSTANCE_VOCABULARY = String.join("\n",
    "Template instance YAML — fills in a template's children with values.",
    "",
    "Top-level keys:",
    "  type             (required) instance",
    "  name             optional   human-readable instance name",
    "  isBasedOn        (required) IRI of the template this instance is based on",
    "  id               optional   absolute IRI of the instance itself. Normally omitted: CEDAR",
    "                              mints it when the instance is created. Distinct from isBasedOn,",
    "                              which points to the template. Supply one only to repeat an id",
    "                              already assigned, e.g.",
    "                              https://repo.metadatacenter.org/template-instances/5c48700a-4163-436d-8daa-95af7311cded",
    "  children:        (required) map of child-key → value, matching the template schema",
    "",
    "Child value shapes (under 'children:' — keyed by the schema's child key):",
    "  text/numeric/temporal/email/phone/link/ext-*/static field:",
    "    plain literal:           value: \"Bob\"      (or just the literal directly)",
    "    typed literal:           value: 33  /  value: 2024-09-12  ",
    "    language-tagged literal: value: \"Bob\", language: en",
    "  controlled-term field:",
    "    id:    <ontology class IRI>",
    "    label: <human label>",
    "  attribute-value field:",
    "    a free-form map of user-keyed entries; each entry has the same shape as a",
    "    text-field instance (value, language).",
    "  multi-instance fields/elements:",
    "    a list of the above instance shapes, one per occurrence.",
    "",
    "Example — a tiny instance:",
    "  type: instance",
    "  name: Subject 042",
    "  isBasedOn: https://repo.metadatacenter.org/templates/abc-123",
    "  children:",
    "    patient_name:",
    "      value: Bob",
    "      language: en",
    "    age:",
    "      datatype: xsd:int",
    "      value: 33",
    "    diagnosis:",
    "      id: http://purl.obolibrary.org/obo/DOID_4",
    "      label: disease");

  // -----------------------------------------------------------------------
  // Composed blocks — what each *_to_json tool sends to the LLM.
  // -----------------------------------------------------------------------

  /** Full vocabulary for template / element authoring (includes children, configuration). */
  static String fullSchemaVocabulary()
  {
    return String.join("\n\n",
      COMMON_SCHEMA_KEYS,
      FIELD_TYPES_AND_KEYS,
      CHILDREN_BLOCK,
      CHILD_CONFIGURATION,
      EXAMPLES);
  }

  /** Vocabulary for a standalone field — no children/configuration apply. */
  static String fieldOnlyVocabulary()
  {
    return String.join("\n\n",
      COMMON_SCHEMA_KEYS,
      FIELD_TYPES_AND_KEYS,
      EXAMPLES);
  }

  /** Vocabulary for a template instance — keyed by child name, not schema discriminators. */
  static String instanceVocabulary()
  {
    return INSTANCE_VOCABULARY;
  }
}

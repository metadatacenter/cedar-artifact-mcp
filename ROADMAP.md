# Roadmap

This file tracks what's built, what's planned, and what's deliberately out of scope.

## Done

### Scaffold

- Maven scaffold with the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp:1.1.3`)
  and `cedar-artifact-library:2.8.1-SNAPSHOT` (tracking the library's `develop` branch).
- Stdio transport server with a diagnostic `ping` tool.
- Shaded executable jar build (`mvn package` → `target/cedar-artifact-mcp-<v>-all.jar`).
- Jackson 2.x / Jackson 3.x classpath conflict resolved via explicit shade filters.
- Two-tier test stack: surefire unit tests (`*Test.java`) and failsafe end-to-end
  ITs (`EndToEndStdioIT`) that spawn the shaded jar and speak real JSON-RPC.

### Empty-shell builders

- `create_template(name, description?, version?)` — validated with `CedarValidator`.
- `create_element(name, description?, version?)`.
- `create_field(name, type, description?, version?)` — `type` is the kebab-case
  vocabulary as `field_from_yaml` (24 variants plus multi-select-list-field).
  Numeric and temporal fields receive sensible defaults for the otherwise-required
  numberType / temporalType / granularity invariants.

### YAML ↔ JSON Schema transcoders

- `template_from_yaml(yaml)` — **the headline authoring tool.**
- `element_from_yaml(yaml)` — element variant.
- `field_from_yaml(yaml)` — field variant.
- `template_to_yaml(json, isCompact?)` / `element_to_yaml(json, isCompact?)` /
  `field_to_yaml(json, isCompact?)` — reverse direction. The `isCompact` boolean
  defaults to `true` (LLM-friendly, omits provenance/status/version/modelVersion).
  Both forms round-trip through the matching `*_from_yaml` tool.

### Incremental builders

- `add_field(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)` /
  `add_element(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`
  — adds an existing child JSON to a template or element parent. Parent kind is
  inferred from `@type`. Optional per-add-site overrides for key, label,
  description, multi-instance flag, and cardinality bounds.

### Value-constraint tools (controlled-term fields)

- `add_class_constraint(field_json, class_iri, ontology_acronym, label, pref_label, value_type?)`.
- `add_ontology_constraint(field_json, ontology_iri, ontology_acronym, ontology_name)`.
- `add_branch_constraint(field_json, ontology_name, ontology_acronym, branch_iri, branch_label, max_depth?)`.
- `add_valueset_constraint(field_json, value_set_iri, vs_collection, name)`.

All four accept any TEXTFIELD-shape field; the library only classifies a TEXTFIELD
as controlled-term once it carries a constraint (an empty controlled-term-field
and a text-field are JSON-indistinguishable on the wire).

### Instances

- `create_instance(template_json, name?, description?, is_based_on?)` — walks a
  template and produces an empty instance skeleton that validates against it.
- `instance_from_yaml(yaml)` / `instance_to_yaml(json, isCompact?)`.
- `validate_instance(template_json, instance_json)` — `CedarValidator.validateTemplateInstance`.
- `set_field_value(template_json, instance_json, field_path, value)` — literal-valued
  fields (text, numeric, temporal, phone, email, radio, checkbox, list, text-area).
- `set_iri_field_value(template_json, instance_json, field_path, iri, label?)` — IRI
  fields (link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, DOI).
- `set_controlled_term_field_value(template_json, instance_json, field_path, iri, label, pref_label?)`
  — controlled-term fields. Slash-separated `field_path` supports nested elements.

## Authoring strategy

YAML is the primary authoring serialization (compact, hierarchical, LLM-friendly).
CEDAR JSON Schema is the canonical output because downstream CEDAR tooling consumes
JSON Schema, not YAML — every authoring tool ends in JSON Schema.

## Next

These are the open items, ordered roughly by how likely a real authoring workflow
would hit them.

- **`add_default_value(field_json, value)`** — set a field's default value at
  schema-build time. Each field type takes a different value shape so this'll
  likely be a small family (`add_text_default_value`, `add_numeric_default_value`,
  `add_iri_default_value` / `add_controlled_term_default_value`). Defer until a
  concrete need surfaces.

- **Multi-instance value indexing on the setters** — `set_field_value`,
  `set_iri_field_value`, `set_controlled_term_field_value` currently only handle
  single-instance fields and walk through single-instance elements. To populate
  the 3rd address in a multi-instance address element, callers need an index in
  the path syntax (e.g. `addresses[2]/street`) or a dedicated multi-instance
  append/replace tool.

- **Removing or replacing children** — `remove_field` / `remove_element` to undo
  an add. Currently the only way to "change your mind" is to rebuild the parent
  from scratch via YAML.

- **Attribute-value field instance support in `create_instance`** — the walker
  currently skips attribute-value fields (they live in their own
  `attributeValueFieldInstanceGroups` map, not the regular single/multi-instance
  maps). Add when a workflow needs them.

## Known limitation

The CEDAR model treats an empty controlled-term-field as JSON-indistinguishable
from a plain text-field (a TEXTFIELD becomes ControlledTermField only once it
carries a constraint). The constraint tools and `set_controlled_term_field_value`
work around this; the proper fix is scheduled for the next model version.

## Out of scope

The following belong in other MCPs (or do not belong in any MCP at all):

- Terminology lookups — use `bioportal-term-mcp` (or a future OLS-backed equivalent).
- Talking to a CEDAR repository server (workspace creation, template publishing).
- Excel, REDCap, CDISC, or other foreign-format exports.
- Sample / dataset generation against templates.
- Stateful handle-based reader/render tools — the MCP is intentionally stateless;
  every tool takes and returns JSON. See DESIGN.md Principle 3.

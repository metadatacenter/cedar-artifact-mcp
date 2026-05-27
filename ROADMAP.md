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
- `remove_child(parent_json, key)` — removes a field or element child by key.
  Auto-detects child kind and removes the parent's `_ui` order/label/description
  entries in lockstep.

### Value-constraint tools (controlled-term fields)

- `add_class_constraint(field_json, class_iri, ontology_acronym, label, pref_label, value_type?)`.
- `add_ontology_constraint(field_json, ontology_iri, ontology_acronym, ontology_name)`.
- `add_branch_constraint(field_json, ontology_name, ontology_acronym, branch_iri, branch_label, max_depth?)`.
- `add_valueset_constraint(field_json, value_set_iri, vs_collection, name)`.

All four accept any TEXTFIELD-shape field; the library only classifies a TEXTFIELD
as controlled-term once it carries a constraint (an empty controlled-term-field
and a text-field are JSON-indistinguishable on the wire).

### Default values (schema-side)

- `add_default_value(field_json, value)` — literal-valued fields (text, text-area,
  numeric, temporal, phone, email, radio, checkbox, list).
- `add_iri_default_value(field_json, iri)` — IRI fields (bare URI; the library's
  schema-side default doesn't carry a label).
- `add_controlled_term_default_value(field_json, iri, label)` — controlled-term
  fields only. Stricter than the constraint tools: a plain text-field is refused
  with a redirect to `add_*_constraint` first.

### Instances

- `create_instance(template_json, name?, description?, is_based_on?)` — walks a
  template and produces an empty instance skeleton that validates against it.
  Attribute-value fields are seeded as empty groups; static fields are skipped.
- `instance_from_yaml(yaml)` / `instance_to_yaml(json, isCompact?)`.
- `validate_instance(template_json, instance_json)` — `CedarValidator.validateTemplateInstance`.
- `set_field_value(template_json, instance_json, field_path, value)` — literal-valued
  fields (text, numeric, temporal, phone, email, radio, checkbox, list, text-area).
- `set_iri_field_value(template_json, instance_json, field_path, iri, label?)` — IRI
  fields (link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, DOI).
- `set_controlled_term_field_value(template_json, instance_json, field_path, iri, label, pref_label?)`
  — controlled-term fields. `field_path` is slash-separated with optional bracketed
  indices for multi-instance children (`address/street`, `addresses[2]/street`,
  `emails[0]`). Index equal to current list size on a multi-instance field leaf
  appends; multi-instance element steps must already exist.

## Authoring strategy

YAML is the primary authoring serialization (compact, hierarchical, LLM-friendly).
CEDAR JSON Schema is the canonical output because downstream CEDAR tooling consumes
JSON Schema, not YAML — every authoring tool ends in JSON Schema.

## Next

These are the open items, ordered roughly by how likely a real authoring workflow
would hit them.

- **Numeric default-value validator quirk** — `add_default_value` builds and
  renders for numeric fields, but the library writes the default as a plain JSON
  number while CedarValidator's schema for that location expects a string or
  object. Library/validator alignment issue, not a tool issue.

- **Replacing children in place** — `remove_child` lands a child to remove an
  existing child; replacing in place still requires remove + add. A dedicated
  `replace_child` would be ergonomic if a workflow needs it.

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

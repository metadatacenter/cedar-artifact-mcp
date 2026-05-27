# Roadmap

This file tracks what's built, what's planned, and what's deliberately out of scope.

## Done

- Maven scaffold with the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp:1.1.3`)
  and `cedar-artifact-library:2.8.1-SNAPSHOT` (tracking the library's `develop` branch).
- Stdio transport server with a diagnostic `ping` tool.
- Shaded executable jar build (`mvn package` → `target/cedar-artifact-mcp-<v>-all.jar`).
- Jackson 2.x / Jackson 3.x classpath conflict resolved via explicit shade filters.
- Two-tier test stack: surefire unit tests (`*Test.java`) and failsafe end-to-end
  ITs (`EndToEndStdioIT`) that spawn the shaded jar and speak real JSON-RPC.
- `create_template(name, description?, version?)` — empty-shell template builder,
  validated with `CedarValidator` before returning.
- `create_element(name, description?, version?)` — empty-shell element builder.
- `create_field(name, type, description?, version?)` — empty-shell field builder;
  `type` is the same kebab-case vocabulary as `field_from_yaml` (24 variants plus
  multi-select-list-field). Numeric and temporal fields receive sensible defaults
  for the otherwise-required numberType / temporalType / granularity invariants.
- `template_from_yaml(yaml)` — **the headline authoring tool.** Compiles a CEDAR
  template described in YAML to the canonical CEDAR JSON Schema; validates with
  `CedarValidator` end-to-end.
- `element_from_yaml(yaml)` — element variant, same pipeline as `template_from_yaml`,
  validating with `validateTemplateElement`.
- `field_from_yaml(yaml)` — field variant, validating with `validateTemplateField`.
- `template_to_yaml(json, isCompact?)` / `element_to_yaml(json, isCompact?)` /
  `field_to_yaml(json, isCompact?)` — reverse direction. JSON Schema in → YAML out, via
  the library's `JsonArtifactReader` + `YamlArtifactRenderer`. The `isCompact` boolean
  defaults to `true` (LLM-friendly, omits provenance/status/version/modelVersion).
  Set `false` for full-fidelity output that carries provenance and version metadata.
  Both forms round-trip through the matching `*_from_yaml` tool.
- `add_field(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)` /
  `add_element(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)` —
  adds an existing child JSON (typically from the matching `create_*` or `*_from_yaml`
  tool) as a child of a template or element JSON. Parent kind is inferred from the
  `@type` URI. The optional per-add-site overrides: `key` (defaults to child's
  `schema:name`); `name` and `description` (override the parent's `_ui` propertyLabel /
  propertyDescription); `isMultiInstance` (default `false`); and `minItems` / `maxItems`
  (cardinality bounds when multi-instance).

## Authoring strategy

YAML is the primary authoring serialization (compact, hierarchical, LLM-friendly).
CEDAR JSON Schema is the canonical output because downstream CEDAR tooling consumes
JSON Schema, not YAML — every authoring tool ends in JSON Schema.

- Value-constraint tools for controlled-term fields:
  - `add_class_constraint(field_json, class_iri, ontology_acronym, label, pref_label, value_type?)`.
  - `add_ontology_constraint(field_json, ontology_iri, acronym, name)`.
  - `add_branch_constraint(field_json, branch_iri, ontology_name, ontology_acronym, branch_label, max_depth?)`.
  - `add_valueset_constraint(field_json, value_set_iri, vs_collection, name)`.

  All four accept any TEXTFIELD-shape field; the library only classifies a TEXTFIELD as
  controlled-term once it carries a constraint, so an empty controlled-term-field and a
  text-field are JSON-indistinguishable on the wire.

## Next — defaults and instances

- `add_default_value(field_json, value)` — set a default value on a field. Each field
  type takes a different value shape, so this'll likely be a small family of tools
  rather than one. Defer until a concrete authoring need surfaces.

### Instances

- `create_instance(template_handle)`.
- `set_field_value(instance_handle, field_path, value)`.
- `set_controlled_term_field(instance_handle, field_path, class_iri, label)`.

### Readers / renderers

- `read_template_json(json_text)` → template handle.
- `read_instance_json(json_text)` → instance handle.
- `render_template_json(template_handle)`.
- `render_instance_json(instance_handle)`.
- YAML variants once the YAML round-trip work in the library lands.

### Validators

- `validate_instance(template_handle, instance_handle)` → validation report.

## Out of scope

The following belong in other MCPs (or do not belong in any MCP at all):

- Terminology lookups — use `bioportal-term-mcp` (or a future OLS-backed equivalent).
- Talking to a CEDAR repository server (workspace creation, template publishing).
- Excel, REDCap, CDISC, or other foreign-format exports.
- Sample / dataset generation against templates.

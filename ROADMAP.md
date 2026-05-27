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

## Authoring strategy

YAML is the primary authoring serialization (compact, hierarchical, LLM-friendly).
CEDAR JSON Schema is the canonical output because downstream CEDAR tooling consumes
JSON Schema, not YAML — every authoring tool ends in JSON Schema.

## Next — incremental builders (the escape hatch)

These exist for cases YAML can't cleanly cover: interleaving with terminology MCPs,
mutating an existing JSON template, composing from non-YAML sources. Lower priority
than the transcoders.

- `add_field(parent_json, field_type, key, name, description?, required?)` — one tool
  with a `field_type` enum discriminator covering all 24 field types.
- `add_element_to_parent(parent_json, child_json, key, name)`.

### Value constraints

One tool per constraint kind. The canonical tuple inputs match what the artifact
library expects; they intentionally do not assume any particular upstream provided them.

- `add_ontology_constraint(field_handle, ontology_iri, acronym, name)`.
- `add_branch_constraint(field_handle, ontology_iri, acronym, name, branch_iri, branch_label)`.
- `add_class_constraint(field_handle, class_iri, ontology_acronym, label, source?)`.
- `add_valueset_constraint(field_handle, value_set_iri, vs_collection, name, num_terms?)`.
- `add_default_value(field_handle, value)`.

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

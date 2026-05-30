# Roadmap

What's built, what's planned, and what's deliberately out of scope. For the
architectural principles see [DESIGN.md](./DESIGN.md).

## Done

### Scaffold

- Maven scaffold with the official MCP Java SDK and `cedar-artifact-library:2.8.1-SNAPSHOT`
  (tracking the library's `develop` branch).
- Stdio transport server with a diagnostic `ping` tool.
- Shaded executable jar (`mvn package` → `target/cedar-artifact-mcp-<v>-all.jar`); the
  Jackson 2.x / 3.x classpath conflict is resolved via explicit shade filters.
- Two-tier tests: surefire unit tests (`*Test.java`) and a failsafe `EndToEndStdioIT`
  that spawns the shaded jar and speaks real JSON-RPC over stdio.

### Exchange format — YAML

Artifacts thread between tools as YAML; the in-memory model is canonical and the
serialization is just transport (DESIGN.md Principle 8). The `ArtifactExchange` helper
centralizes read (YAML or JSON, auto-detected) and render. Artifact-returning tools take
an optional `isCompact` flag:

- **Schema artifacts (template/element/field) default to compact** — the lean form that
  drops provenance (status/version/modelVersion) but keeps the full structure and `@id`.
  Pass `isCompact: false` for the expanded, fully-provenanced form to persist to a repository.
- **Instances default to expanded** — a skeleton/partial instance's value-less field slots
  are structural (`set_field_value` needs them) and compact would elide them. Pass
  `isCompact: true` to display a finished instance leanly.
- Every returned artifact has round-tripped through the library and passed `CedarValidator`
  (rendered to JSON internally — DESIGN.md Principle 6).

### Builders

- `create_template(name, description?, version?, id?, isCompact?)`
- `create_element(name, description?, version?, id?, isCompact?)`
- `create_field(name, type, description?, version?, id?, isCompact?, [type-specific config])`
  — `type` is the kebab-case vocabulary (text / text-area / numeric / temporal / radio /
  checkbox / single- and multi-select list / controlled-term / link / email / phone, the
  `ext-*` identifier fields, and the `static-*` placeholders). Numeric and temporal fields
  receive sensible defaults for the otherwise-required numberType / temporalType / granularity.
- A top-level `@id` is auto-minted of the correct CEDAR form when omitted (DESIGN.md
  Principle 10: `templates` / `template-elements` / `template-fields` / `template-instances`);
  nested children are never minted.

### Incremental builders

- `add_field` / `add_element(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?, isCompact?)`
  — graft an existing child artifact onto a template or element parent; parent kind inferred
  from the artifact. Per-add-site overrides for key, label, description, multi-instance flag,
  and cardinality bounds.
- `remove_child(parent, key, isCompact?)` — removes a field or element child, updating the
  parent's `_ui` order / label / description entries in lockstep.

### Controlled-term constraints

- `set_class_constraint`, `set_ontology_constraint`, `set_branch_constraint`,
  `set_valueset_constraint` (each `(field, …, isCompact?)`) — attach a value constraint to a
  controlled-term field. The canonical input tuples match what `bioportal-term-mcp` returns.
  All accept any TEXTFIELD-shape field; the library classifies a TEXTFIELD as controlled-term
  only once it carries a constraint (an empty controlled-term-field and a text-field are
  wire-indistinguishable until then).

### Default values (schema-side)

- `set_default_value` — literal-valued fields (text, text-area, numeric, temporal, phone,
  email, radio, checkbox, list).
- `set_iri_default_value` — IRI fields (link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID,
  DOI); a bare URI, since the schema-side default carries no label.
- `set_controlled_term_default_value` — controlled-term fields only; refuses an unconstrained
  text-field with a redirect to the constraint tools.

### Instances

- `create_instance(template, name?, description?, is_based_on?, id?, isCompact?)` — walks a
  template and produces an empty instance skeleton that validates against it. Attribute-value
  fields are seeded as empty groups; static fields are skipped; the instance `@id` is
  auto-minted when omitted.
- `set_field_value` / `set_iri_field_value` / `set_controlled_term_field_value` — set a value
  at a slash-separated `field_path` (bracketed indices for multi-instance leaves, e.g.
  `address/street`, `addresses[2]/street`, `emails[0]`; an index equal to the current list
  size appends).
- `validate_instance(template, instance)` — `CedarValidator.validateTemplateInstance`.

### Export / import

- `template_to_json` / `element_to_json` / `field_to_json` / `instance_to_json` — **export** a
  (YAML) artifact to the canonical CEDAR JSON Schema that cedar-server and other downstream
  tooling consume. Validated before returning.
- `template_to_yaml` / `element_to_yaml` / `field_to_yaml` / `instance_to_yaml(artifact, isCompact?)`
  — render any artifact (YAML or JSON, auto-detected) as YAML. Two jobs: **recompact** an
  expanded artifact for a lean display (`isCompact: true`, no JSON detour), and **import** an
  external JSON Schema artifact into the YAML loop.

## Next

- **Replacing children in place** — replacing a child still requires `remove_child` + an
  `add_*`. A dedicated `replace_child` would be ergonomic if a workflow needs it.

- **Collapse compact/expanded into one "render-if-present" YAML form** — idea, not yet
  decided. Today artifact-returning tools default to compact (schema artifacts) or expanded
  (instances) and expose an `isCompact` flag. A simpler model: drop the flag and the
  compact/expanded distinction entirely, and instead render provenance (status, version,
  modelVersion, created/modified, …) only when it is actually set — absent ⇒ omitted, present
  ⇒ shown and round-tripped. Most freshly authored artifacts set none of it, so the default
  view is naturally lean, and there is no lossy compaction (so the "provenance dropped by a
  compact hop can't be recovered" footgun disappears). Instances would simply always render
  their value slots (structural) plus any set provenance, removing the instance-specific
  default. `modelVersion` (always `1.6.0`) would be omitted and re-defaulted on read.

  The load-bearing prerequisite: this only yields a lean default if `version` / `status` stop
  being *injected* as defaults — both here (the MCP defaults `version` to `0.0.1`) and in the
  library builder (defaults `0.0.1` / `draft`). That builder change is the reader-vs-builder
  defaulting asymmetry already on the library ROADMAP and has cross-consumer reach
  (cedar-server tooling, the CLI), so it needs coordinating, not just an MCP edit. Tradeoff to
  weigh: a single form can no longer *hide* provenance that does exist (e.g. a server-loaded
  artifact's timestamps), which compact could.

Library-side items that surface through this MCP but whose fix lives in
[`cedar-artifact-library`](https://github.com/metadatacenter/cedar-artifact-library/blob/develop/ROADMAP.md)
are tracked there — at time of writing, the reader-vs-builder `version` / `status` defaulting
asymmetry the idea above depends on.

## Known limitation

The CEDAR model treats an empty controlled-term-field as JSON-indistinguishable from a plain
text-field (a TEXTFIELD becomes ControlledTermField only once it carries a constraint). The
constraint tools and `set_controlled_term_field_value` work around this; the proper fix is
scheduled for the next model version.

## Out of scope

The following belong in other MCPs (or do not belong in any MCP at all):

- Terminology lookups — use `bioportal-term-mcp` (or a future OLS-backed equivalent).
- Talking to a CEDAR repository server (workspace creation, template publishing).
- Excel, REDCap, CDISC, or other foreign-format exports.
- Sample / dataset generation against templates.
- Stateful handle-based tools — the MCP is intentionally stateless; every tool takes and
  returns a serialized artifact. See DESIGN.md Principle 3.

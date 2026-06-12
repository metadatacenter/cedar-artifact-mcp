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
centralizes read (YAML or JSON, auto-detected) and render.

- **Every mutating tool returns the expanded, lossless exchange form** — version, status,
  modelVersion, and value-less instance slots always carried, so nothing is silently
  dropped between tool calls. `isCompact` is a display choice and lives only on the
  `*_to_yaml` rendering tools (`isCompact: true` drops the provenance keys).
- The schema-artifact `create_*` tools take optional `version` and `status` (defaults
  `0.0.1` / `draft`).
- Every returned artifact has round-tripped through the library and passed `CedarValidator`
  (rendered to JSON internally — DESIGN.md Principle 6).

### Builders

- `create_template(name, description?, version?, status?, id?)`
- `create_element(name, description?, version?, status?, id?)`
- `create_field(type, name, description?, version?, status?, id?, [type-specific config])`
  — `type` is the kebab-case vocabulary (text / text-area / numeric / temporal / radio /
  checkbox / single- and multi-select list / controlled-term / link / email / phone, the
  `ext-*` identifier fields, and the `static-*` placeholders). Numeric and temporal fields
  receive sensible defaults for the otherwise-required numberType / temporalType / granularity.
- A top-level `@id` is auto-minted of the correct CEDAR form when omitted (DESIGN.md
  Principle 10: `templates` / `template-elements` / `template-fields` / `template-instances`);
  nested children are never minted.

### Incremental builders

- `add_field(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?, isRequired?, isHidden?)` /
  `add_element(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`
  — graft an existing child artifact onto a template or element parent; parent kind inferred
  from the artifact. Per-add-site overrides for key, label, description, multi-instance flag,
  and cardinality bounds.
- `replace_field(parent, child, key, …)` / `replace_element(parent, child, key, …)` — replace
  the child at a key in place, keeping its position in the parent's display order (where
  `remove_child` + `add_*` would append). Same per-add-site overrides as the `add_*` pair.
- `remove_child(parent, key)` — removes a field or element child, updating the
  parent's `_ui` order / label / description entries in lockstep.

### Controlled-term constraints

- `set_class_constraint`, `set_ontology_constraint`, `set_branch_constraint`,
  `set_valueset_constraint` (each `(field, …)`) — attach a value constraint to a
  controlled-term field. The canonical input tuples match what `bioportal-term-mcp` returns.
  All accept any TEXTFIELD-shape field; the library classifies a TEXTFIELD as controlled-term
  only once it carries a constraint (an empty controlled-term-field and a text-field are
  wire-indistinguishable until then).

### Literal options

- `set_options(field, options, default_option?)` — replaces a choice field's (radio /
  checkbox / single- and multi-select list) literal option list, in display order, with an
  optional pre-selected default; `create_field` accepts the same `options` inline. The
  literal-values counterpart of the `set_*_constraint` family.

### Default values (schema-side)

- `set_literal_default_value` — literal-valued fields (text, text-area, numeric, temporal,
  phone, email, radio, checkbox, list).
- `set_iri_default_value` — IRI fields (link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID,
  DOI), where the schema-side default is a bare URI, and controlled-term fields, where it is
  the class IRI plus a required `label`; an unconstrained text-field is refused with a
  redirect to the constraint tools.

### Instances

- `create_instance(template, name?, description?, id?)` — walks a
  template and produces an empty instance skeleton that validates against it. Attribute-value
  fields are seeded as empty groups; static fields are skipped; the instance `@id` is
  auto-minted when omitted.
- `set_literal_field_value` / `set_iri_field_value` — set a value (the latter covers plain
  IRI fields and controlled-term fields, which require a `label`)
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

- **Build without a locally installed library** — building this MCP requires
  `cedar-artifact-library:2.8.1-SNAPSHOT` to have been `mvn install`ed from a local checkout
  of the library's `develop` branch — and that library in turn sits atop `cedar-parent`,
  `cedar-model-library`, and `cedar-model-validation-library`, so a fresh machine must clone
  and install four repositories in dependency order; none of the snapshots resolve from any
  public repository. That makes the MCP heavier to distribute than it needs to be (the
  prebuilt shaded jar is the workaround). The fix lives on the library side — publish
  released, non-SNAPSHOT artifacts to a public Maven repository and pin this MCP to a
  released version. `cedar-cee-mcp` already resolves entirely from Maven Central and is the
  target state.

- **Child ordering is not exposed.** CEDAR templates and elements carry an explicit child
  order for display purposes (the `_ui.order` list; the order forms render fields in), and
  the model round-trips it — but this MCP offers no way to control it. `add_child` always
  appends, so the only route to a particular order is adding children in that order, and
  re-ordering an existing parent has no route at all (hand-editing the YAML is ruled out by
  the verbatim directive). Expose it — either an optional `position` on `add_child`, or a
  dedicated `reorder_children(parent, keys)` that takes the full key list and re-orders the
  parent's `_ui.order` (the library prunes children absent from the order, so the tool must
  require a complete permutation of the existing keys).

- **Render-if-present as the one YAML form** — idea, not yet decided. Mutating tools now
  always return the expanded exchange form and `isCompact` survives only on the `*_to_yaml`
  rendering tools; the remaining question is whether the compact/expanded distinction could
  disappear entirely. A render-if-present form would emit provenance (status, version,
  modelVersion, created/modified, …) only when actually set — absent ⇒ omitted, present ⇒
  shown and round-tripped — making the default view naturally lean with no lossy compaction.

  The load-bearing prerequisite: that only yields a lean default if `version` / `status` /
  `modelVersion` stop being *injected* as defaults — both here and in the library builder
  and readers (which now default them deliberately). That has cross-consumer reach
  (cedar-server tooling, the CLI), so it needs coordinating, not just an MCP edit. Tradeoff
  to weigh: a single form can no longer *hide* provenance that does exist (e.g. a
  server-loaded artifact's timestamps), which `isCompact: true` can.

Library-side items that surface through this MCP but whose fix lives in
[`cedar-artifact-library`](https://github.com/metadatacenter/cedar-artifact-library/blob/develop/ROADMAP.md)
are tracked there — at time of writing, the reader-vs-builder `version` / `status` defaulting
asymmetry the idea above depends on.

## Known limitation

The CEDAR model treats an empty controlled-term-field as JSON-indistinguishable from a plain
text-field (a TEXTFIELD becomes ControlledTermField only once it carries a constraint). The
constraint tools and `set_iri_field_value`'s controlled-term branch work around this; the
proper fix is
scheduled for the next model version.

## Out of scope

The following belong in other MCPs (or do not belong in any MCP at all):

- Terminology lookups — use `bioportal-term-mcp` (or a future OLS-backed equivalent).
- Talking to a CEDAR repository server (workspace creation, template publishing).
- Excel, REDCap, CDISC, or other foreign-format exports.
- Sample / dataset generation against templates.
- Stateful handle-based tools — the MCP is intentionally stateless; every tool takes and
  returns a serialized artifact. See DESIGN.md Principle 3.
- Setting provenance (`pav:createdOn` / `pav:createdBy`, `oslc:modifiedBy`, …) — these are
  assigned by a repository server, so a construction-side tool for them would only fabricate
  history. The readers and renderers round-trip whatever provenance a server-loaded artifact
  carries, losslessly, which is the right behavior for a stateless MCP.
- Version chains (`pav:previousVersion`, `pav:derivedFrom`) — only meaningful against a
  repository that holds the artifacts they point at. Same treatment: preserved on round-trip,
  never authored here.

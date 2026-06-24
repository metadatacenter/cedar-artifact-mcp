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
  Static fields take their `content` (rich-text body, image URL, video URL, section text) and,
  for image/video, `width` / `height`; there is deliberately no set_static_content — static
  fields carry no constraints or defaults, so edit-by-recreate plus `replace_field` covers it.
- A top-level `@id` is auto-minted of the correct CEDAR form when omitted (DESIGN.md
  Principle 10: `templates` / `template-elements` / `template-fields` / `template-instances`);
  nested children are never minted.

### Incremental builders

- `add_field(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?, isRequired?, isHidden?, property_iri?)` /
  `add_element(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?, property_iri?)`
  — graft an existing child artifact onto a template or element parent; parent kind inferred
  from the artifact. Per-add-site overrides for key, label, description, multi-instance flag,
  cardinality bounds, and the ontology property the child maps to in instances (`property_iri`,
  the JSON-LD `@context` mapping for the key).
- `reorder_children(parent, keys)` — sets the display order (`_ui.order`) from a complete
  permutation of the existing child keys; partial lists are rejected (the library prunes
  children absent from the order, so a partial list would delete). Declarative and
  idempotent; one tool for both child kinds, static fields included. Instance-side: child
  order never affects validity, and every instance the tools return is serialized in its
  template's display order (the inflater canonicalizes order on every instance operation).
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
  wire-indistinguishable until then). Constraints accumulate across calls.
- `remove_constraint(field, iri)` — the inverse, kind-blind: every constraint kind is
  identified by the IRI it points at. Removing the last constraint yields a text-field-shaped
  field, and is refused while a controlled-term default would be orphaned.

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

- `create_template_instance(template, name?, description?, id?)` — walks a
  template and produces an empty instance skeleton that validates against it. Attribute-value
  fields are seeded as empty groups; static fields are skipped; the instance `@id` is
  auto-minted when omitted.
- `create_element_instance(element, name?, description?, id?)` — the element counterpart:
  an empty element instance as a standalone `type: element-instance` document (`@id` minted in the
  `template-element-instances` collection), to be grafted in with `set_element_instance`.
  Backed by the library's standalone element-instance serialization (readers, renderers, and
  the YAML `element-instance` document kind added for this).
- `set_element_instance(template, instance, field_path, element_instance)` — grafts an
  element instance at an element path: single-instance replaces; `addresses[N]` replaces entry N or
  appends when N equals the list size. This is what makes multi-instance elements fillable —
  entries can now be appended, filled via `addresses[N]/...` paths, and deleted with
  `unset_field_value`.
- `set_literal_field_value` / `set_iri_field_value` — set a value (the latter covers plain
  IRI fields and controlled-term fields, which require a `label`)
  at a slash-separated `field_path` (bracketed indices for multi-instance leaves, e.g.
  `address/street`, `addresses[2]/street`, `emails[0]`; an index equal to the current list
  size appends).
- `unset_field_value(template, instance, field_path)` — the inverse of the setters, one tool
  for all field kinds: a single-instance path clears the value, an indexed multi-instance path
  deletes the entry, an unindexed multi-instance path clears the list. Idempotent; required
  fields may be unset (`requiredValue` is enforced at validation time).
- `set_attribute_value(template, instance, field_path, attribute_name, value)` /
  `unset_attribute_value(template, instance, field_path, attribute_name)` — populate an
  attribute-value field, whose dynamic name→value entries are entered at fill time. field_path
  locates the field (the group); attribute_name is the user-chosen key; value is its literal
  string (attribute values are literal-only — the generated JSON Schema requires `@value` and
  forbids `@id`). Set overwrites, unset is idempotent; entries are rewritten via the library's
  withAttributeValueFieldGroup / withoutAttributeValueFieldGroup pair.
- `set_literal_annotation(artifact, annotation, value)` / `set_iri_annotation(artifact,
  annotation, iri)` / `remove_annotation(artifact, annotation)` — attach metadata annotations
  (a property-IRI/CURIE → literal-or-IRI value pair) at an artifact's root. Accepts a template,
  element, field, or template instance (kind auto-detected); set overwrites, remove is
  idempotent. Edits the JSON-LD `_annotations` map at the node level (all four kinds carry it
  identically). Element instances do not carry annotations.
- `validate_instance_artifact(schema_artifact, instance_artifact)` — validates a template or
  element instance against the schema it is based on; the schema kind is auto-detected from its
  `@type`. Template → `CedarValidator.validateTemplateInstance`; element →
  `validateElementInstance` with the element as the schema document (an element artifact is
  itself the JSON Schema its instances validate against), the element instance checked in its
  nested shape.

### Export / import

- `render_schema_artifact(schema_artifact, format?, compact?)` / `render_instance_artifact(instance_artifact, template_artifact?, format?, compact?)`
  — render an artifact (YAML or JSON, auto-detected; kind auto-detected) to YAML (default) or
  JSON. `format: yaml` does two jobs: **recompact** an expanded artifact for a lean display
  (`compact: true`, no JSON detour) and **import** an external JSON artifact into the YAML loop.
  `format: json` is the **export** escape hatch for the rare tool that can't read YAML
  (cedar-server itself now reads and writes YAML). The schema renderer auto-detects
  template/element/field; the instance renderer auto-detects template/element instance and, given
  the optional `template_artifact`, inflates the sparse instance to a complete instance. Neither
  validates — rendering renders; validation lives in `validate_schema_artifact` /
  `validate_instance_artifact`.

## Next

- **Build without a locally installed library** — building this MCP requires
  `cedar-artifact-library:2.8.3-SNAPSHOT` to have been `mvn install`ed from a local checkout
  of the library's `develop` branch — and that library in turn sits atop `cedar-parent`,
  `cedar-model-library`, and `cedar-model-validation-library`, so a fresh machine must clone
  and install four repositories in dependency order; none of the snapshots resolve from any
  public repository. That makes the MCP heavier to distribute than it needs to be (the
  prebuilt shaded jar is the workaround). The fix lives on the library side — publish
  released, non-SNAPSHOT artifacts to a public Maven repository and pin this MCP to a
  released version. `cedar-cee-mcp` already resolves entirely from Maven Central and is the
  target state.

- **Field question metadata is not exposed.** The library carries several per-field
  presentation properties with no tool path: `skos:prefLabel` (the preferred question text —
  an alternative phrasing of the field's name shown in forms, distinct from the value-level
  labels), `skos:altLabel` (further alternative phrasings), `language`, and
  `valueRecommendationEnabled`. All preserved on round-trip, none settable.

- **Template header / footer are not exposed.** `TemplateUi` carries display header and
  footer text (`withHeader` / `withFooter`); preserved on round-trip, not settable.

- **Constraint actions are not exposed.** Controlled-term constraints carry an optional
  list of `ControlledTermValueConstraintsAction` entries — the per-term keep/delete/move
  tweaks the CEDAR editor applies on top of a class/ontology/branch/value-set binding (e.g.
  pull one class out of an otherwise-included branch). The library models and round-trips
  them; `set_*_constraint` and `remove_constraint` operate at the whole-constraint level and
  don't touch actions. A finer-grained feature, deliberately kept separate from the
  constraint tools.

- **Render-if-present as the one YAML form** — idea, not yet decided. Mutating tools now
  always return the expanded exchange form and `isCompact` survives only on the `*_to_yaml`
  rendering tools; the remaining question is whether the compact/expanded distinction could
  disappear entirely. A render-if-present form would emit provenance (status, version,
  modelVersion, created/modified, …) only when actually set — absent ⇒ omitted, present ⇒
  shown and round-tripped — making the default view naturally lean with no lossy compaction.

  The load-bearing prerequisite is that this only yields a lean default if `version` / `status` /
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

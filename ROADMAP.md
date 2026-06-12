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

- `add_field(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?, isRequired?, isHidden?, property_iri?)` /
  `add_element(parent, child, key?, name?, description?, isMultiInstance?, minItems?, maxItems?, property_iri?)`
  — graft an existing child artifact onto a template or element parent; parent kind inferred
  from the artifact. Per-add-site overrides for key, label, description, multi-instance flag,
  cardinality bounds, and the ontology property the child maps to in instances (`property_iri`,
  the JSON-LD `@context` mapping for the key).
- `reorder_children(parent, keys)` — sets the display order (`_ui.order`) from a complete
  permutation of the existing child keys; partial lists are rejected (the library prunes
  children absent from the order, so a partial list would delete). Declarative and
  idempotent; one tool for both child kinds, static fields included.
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
- `validate_instance(template, instance)` — `CedarValidator.validateTemplateInstance`.
- `validate_element_instance(element, element_instance)` — `CedarValidator.validateElementInstance`
  with the element as the schema document (an element artifact is itself the JSON Schema its
  instances validate against); the element instance is checked in its nested shape.

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

- **Attribute-value instances cannot be populated.** `create_template_instance` seeds an
  attribute-value field as an empty group, and the library models
  `AttributeValueFieldInstance` fully — but no tool adds a name/value pair to the group, so
  a template using attribute-value fields can be authored while its instances cannot be
  filled. The biggest functional hole in the instance-side surface; needs a setter (and,
  with it, a remove) addressing pairs within the group.

- **Annotations are not exposed.** The library's `withAnnotations` carries the arbitrary
  IRI-keyed annotation block any artifact may hold (DataCite-style templates use it); no
  tool sets it. Annotations on input artifacts are preserved on round-trip — they just
  can't be authored here.

- **Field question metadata is not exposed.** The library carries several per-field
  presentation properties with no tool path: `skos:prefLabel` (the preferred question text —
  an alternative phrasing of the field's name shown in forms, distinct from the value-level
  labels), `skos:altLabel` (further alternative phrasings), `language`, and
  `valueRecommendationEnabled`. All preserved on round-trip, none settable.

- **Template header / footer are not exposed.** `TemplateUi` carries display header and
  footer text (`withHeader` / `withFooter`); preserved on round-trip, not settable.

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

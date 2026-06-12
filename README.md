# cedar-artifact-mcp

[CEDAR](https://metadatacenter.org/) — the Center for Expanded Data Annotation
and Retrieval — builds tools for authoring and applying metadata templates
over scientific datasets. The metadata-template
story is the data-side scaffolding behind the FAIR principles: every dataset
is described by an instance of a shared, typed, controlled-vocabulary-aware
template, so downstream tools can reason about the data without per-dataset
glue. A **CEDAR artifact** is one of the building blocks of that story:
a **template** (a typed schema for a metadata record), an **element** (a
reusable sub-schema embedded inside templates or other elements), a **field**
(a typed property — text, numeric, temporal, controlled-term, identifier, …),
or an **instance** (a template populated with values).

This is a [Model Context Protocol](https://modelcontextprotocol.io/) server
that exposes the
[CEDAR artifact library](https://github.com/metadatacenter/cedar-artifact-library)
— its builders, readers, renderers, and validators — as composable tools an
LLM can call. The LLM authors templates and elements, fills in instances,
validates them, and round-trips through the artifact library's compact YAML
serialization for editing.

The server is the model-construction half of a metadata-template pipeline: it
knows how to assemble CEDAR templates, elements, fields, value constraints,
and instances, but it does not perform terminology lookups, talk to a CEDAR
server, or do any other I/O. Terminology MCPs (e.g.
[`bioportal-term-mcp`](https://github.com/metadatacenter/bioportal-term-mcp))
supply the IRI/acronym/name tuples that controlled-term constraints need; the
calling LLM passes those tuples into this MCP's `set_*_constraint` tools.

See [DESIGN.md](./DESIGN.md) for the architectural principles and
[ROADMAP.md](./ROADMAP.md) for what's planned.

## Example workflow

A typical authoring session looks like the following — natural-language prompts
the user gives the LLM, which the LLM translates into MCP tool calls. The
first example exercises the structural and instance tools end-to-end; a
follow-on example adds the controlled-term piece by pairing this MCP with
[`bioportal-term-mcp`](https://github.com/metadatacenter/bioportal-term-mcp).

Two YAML forms are in play. Every tool that builds or modifies an artifact
returns the **expanded exchange form** — provenance (`status`, `version`,
`modelVersion`) always carried, defaulting to `draft` / `0.0.1` / `1.6.0` —
and that is what threads from tool to tool, so nothing set on an artifact is
ever dropped mid-chain. For *display*, the tool descriptions direct the LLM to
show the user the lean **compact view** instead, produced by the matching
`*_to_yaml` tool with `isCompact: true`. The blocks below show what the user
sees: the compact view (the first step also shows the expanded form it was
rendered from).

Every top-level artifact is auto-assigned an `@id` of the right CEDAR form when
you don't supply one — `https://repo.metadatacenter.org/{templates,
template-elements,template-fields,template-instances}/<uuid>`. The `@id` is
the artifact's *identity*, so the compact view keeps it; it shows up as the
`id:` line in the YAML below. Minting is top-level only: a child authored
inline inside a parent (a field in a template's `children:`, a value in a
controlled-term field) is left id-less unless you set one explicitly — which
is why `Street` and the controlled-term values further down carry no `id:`.

*Create a template called Patient Study.*

The tool returns the expanded exchange form:

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
status: draft
version: 0.0.1
modelVersion: 1.6.0
```

and the LLM displays the compact view:

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
```

Subsequent steps show only the compact view the user sees.

*Create a text field called Patient Name.*

```yaml
type: text-field
name: Patient Name
id: https://repo.metadatacenter.org/template-fields/a6d4eec6-9798-4487-83eb-41672f1a2680
```

*Create a numeric field called Age with type `xsd:int`.*

```yaml
type: numeric-field
name: Age
id: https://repo.metadatacenter.org/template-fields/7771a4f7-1e5e-44ec-8e9e-13ae9dbccd8e
datatype: xsd:int
```

*Set default value 42 on the Age field.*

```yaml
type: numeric-field
name: Age
id: https://repo.metadatacenter.org/template-fields/7771a4f7-1e5e-44ec-8e9e-13ae9dbccd8e
datatype: xsd:int
default: 42
```

*Add Patient Name and Age to Patient Study.*

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
    id: https://repo.metadatacenter.org/template-fields/a6d4eec6-9798-4487-83eb-41672f1a2680
  - key: Age
    type: numeric-field
    name: Age
    id: https://repo.metadatacenter.org/template-fields/7771a4f7-1e5e-44ec-8e9e-13ae9dbccd8e
    datatype: xsd:int
    default: 42
```

The two fields keep the `@id`s they were minted with when they were created as
standalone artifacts above; adding them into the template doesn't change them.

*Create an element called Address with a text field Street.*

```yaml
type: element
name: Address
id: https://repo.metadatacenter.org/template-elements/3a59a0c0-3b74-41f8-8379-52ce16f02af8
children:
  - key: Street
    type: text-field
    name: Street
```

`Street` is authored inline as a child of the element, so it is not minted an
`@id` — only the top-level `Address` element is.

*Add the Address element to Patient Study.*

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
    id: https://repo.metadatacenter.org/template-fields/a6d4eec6-9798-4487-83eb-41672f1a2680
  - key: Age
    type: numeric-field
    name: Age
    id: https://repo.metadatacenter.org/template-fields/7771a4f7-1e5e-44ec-8e9e-13ae9dbccd8e
    datatype: xsd:int
    default: 42
  - key: Address
    type: element
    name: Address
    id: https://repo.metadatacenter.org/template-elements/3a59a0c0-3b74-41f8-8379-52ce16f02af8
    children:
      - key: Street
        type: text-field
        name: Street
```

*Create an instance of Patient Study.*

```yaml
type: instance
name: Patient Study
id: https://repo.metadatacenter.org/template-instances/5d09635b-737d-4308-9f8b-d1dae0233ea1
isBasedOn: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
```

The instance gets its own minted `@id` (a `template-instances` IRI), distinct
from `isBasedOn`, which is taken from the template's `@id`. An instance carries
no `version` / `status` — those are schema-artifact concerns.

*Set Patient Name to Alice in the instance.*

```yaml
type: instance
name: Patient Study
id: https://repo.metadatacenter.org/template-instances/5d09635b-737d-4308-9f8b-d1dae0233ea1
isBasedOn: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
children:
  Patient Name:
    value: Alice
```

*Set Age to 30 in the instance.*

```yaml
type: instance
name: Patient Study
id: https://repo.metadatacenter.org/template-instances/5d09635b-737d-4308-9f8b-d1dae0233ea1
isBasedOn: https://repo.metadatacenter.org/templates/146cc4f1-b650-4a4a-aa4f-fb78b2813f50
children:
  Patient Name:
    value: Alice
  Age:
    datatype: xsd:int
    value: 30
```

### Controlled-term fields with BioPortal

CEDAR's defining feature is **controlled vocabularies**: a field can be
bound to an ontology class, a whole ontology, a subtree of one, or a curated
value set, so every value carries a stable IRI as well as a human-readable
label.
Authoring those bindings by hand means looking up IRIs in BioPortal and
pasting in the right `iri` / `acronym` / `ontologyName` / `termLabel` tuple
— error-prone, and the IRI is the part the LLM is *least* likely to invent
correctly.

The companion
[`bioportal-term-mcp`](https://github.com/metadatacenter/bioportal-term-mcp)
exposes BioPortal search and lookup as tools an LLM can call —
`find_ontology`, `find_class`, `find_value_set`, `get_class`, `get_ontology`,
`get_value_set` — so the same LLM that's driving the artifact MCP can
discover the IRIs and tuples it needs without leaving the conversation. The
two MCPs are designed to pair: the bioportal one returns exactly the
`iri` / `acronym` / `ontologyName` / `termLabel` shape the artifact MCP's
`set_*_constraint` and `set_controlled_term_field_value` tools take as
input.

A typical pairing — both MCPs available at once:

*Create a CEDAR field called Disease that takes its values from the Disease
branch of the DOID ontology in BioPortal.*

```yaml
type: controlled-term-field
name: Disease
description: Disease term sourced from the Disease branch of the DOID ontology.
id: https://repo.metadatacenter.org/template-fields/b452bf87-ae7b-43de-9437-ae61eea595b0
datatype: iri
values:
  - type: branch
    ontologyName: Human Disease Ontology
    acronym: DOID
    termLabel: disease
    iri: http://purl.obolibrary.org/obo/DOID_4
    maxDepth: 0
```

Under the hood the LLM looks up DOID and its `disease` root class (`DOID_4`)
via `bioportal-term-mcp`, then calls `create_field` followed by
`set_branch_constraint` on the CEDAR side. `maxDepth: 0` means unbounded —
every descendant of `disease` in DOID is permitted.

*Create a new template called Study.*

```yaml
type: template
name: Study
id: https://repo.metadatacenter.org/templates/5c1d5f0e-e019-4839-9961-ee528f78232e
children:
  - key: Disease
    type: controlled-term-field
    name: Disease
    description: Disease term sourced from the Disease branch of the DOID ontology.
    id: https://repo.metadatacenter.org/template-fields/b452bf87-ae7b-43de-9437-ae61eea595b0
    datatype: iri
    values:
      - type: branch
        ontologyName: Human Disease Ontology
        acronym: DOID
        termLabel: disease
        iri: http://purl.obolibrary.org/obo/DOID_4
        maxDepth: 0
```

The LLM adds the Disease field to the new template. The template is auto-assigned
an `@id` (a `templates` IRI), and the Disease field keeps the `@id` it was minted
with when it was created standalone above. The branch `values` are inline value
constraints, not artifacts, so they carry no `@id` of their own — just the
ontology `iri` they point at. The template's `@id` is what the instance's
`isBasedOn` references below.

*Create an instance of this template with a value of sickle cell anemia for
the Disease field.*

```yaml
type: instance
name: Study
id: https://repo.metadatacenter.org/template-instances/d5b860ac-7f34-473a-8ceb-2f9ec50c8b73
isBasedOn: https://repo.metadatacenter.org/templates/5c1d5f0e-e019-4839-9961-ee528f78232e
children:
  Disease:
    id: http://purl.obolibrary.org/obo/DOID_10923
    label: sickle cell anemia
    prefLabel: sickle cell anemia
```

The LLM looks up `sickle cell anemia` in DOID via `bioportal-term-mcp`'s
`find_class` (returning `DOID_10923`), confirms it sits under the branch the
field constrains to, and calls `set_controlled_term_field_value` with the
IRI + label tuple. The instance gets its own minted `@id`; `isBasedOn` is taken
straight from the template's `@id`. Note the `Disease` child's `id:` is the
DOID class IRI of the chosen value, not a minted instance id — it's the value the
field points at. The instance now carries the full identifier + label pair, not
just a free-text string — which is what makes the data downstream-queryable.

## Tools

Each tool below is a thin wrapper over a corresponding operation in the
[cedar-artifact-library](https://github.com/metadatacenter/cedar-artifact-library)
— the same Java library CEDAR's own server-side tooling builds on. The
library is where the heavy lifting lives: typed builders for every artifact
kind, readers and renderers for both the canonical JSON Schema and the
compact YAML serialization, and full CEDAR validation. The MCP exposes that
machinery as MCP tools so an LLM can drive it directly: pulling a template
in and out of YAML, attaching constraints, populating instance values,
validating, and so on, without the calling LLM ever needing to know any
Java. Artifacts move between tools as **YAML** — the expanded exchange form (DESIGN.md
Principle 8). The `create_*` / `add_*` / `set_*` / `remove_*` tools wrap the library's
typed builders and return YAML; the `*_to_json` tools export the canonical JSON Schema
(for cedar-server and other downstream consumers) and `*_to_yaml` imports an external
JSON Schema artifact back into the YAML loop; `validate_instance` calls the canonical
CedarValidator. A non-error result from any tool is guaranteed to have round-tripped
through the library and passed its structural validation.

**The exchange form.** Every tool that builds or modifies an artifact (`create_*`,
`add_*`, `set_*`, `remove_child`) returns the **expanded, lossless YAML**: version, status,
modelVersion, and any other provenance are always carried, so nothing set on an artifact can
be silently dropped as it threads from tool to tool. Compaction is purely a display choice,
and the `isCompact` flag therefore lives only on the `*_to_yaml` rendering tools
(`isCompact: true` drops the provenance keys for a lean view). Each mutating tool's
description directs the LLM to use exactly that for interactive display, while threading
the expanded form onward. Instances are **sparse** in
either form — a field with no value is omitted entirely (no `null`, no `{}`, no empty `[]`);
the empty slots the canonical JSON form requires are reconstructed from the template at the
JSON boundary (`validate_instance`, `instance_to_json`).

### `create_template(name, description?, version?, status?, id?)`

Creates a new, empty CEDAR template. `version` and `status` are optional and default to
`0.0.1` / `draft`. Pass the returned template into
`add_field` or `add_element` to attach children, or into `create_instance` to
make an empty instance of it. `id` is optional: supply one assigned by a CEDAR
repository, or omit it and a fresh `templates` IRI is auto-minted.

### `create_element(name, description?, version?, status?, id?)`

Creates a new, empty CEDAR element — a reusable sub-schema that can be embedded
inside templates or other elements. `id` is optional; omit it and a fresh
`template-elements` IRI is auto-minted.

### `create_field(name, type, description?, version?, status?, id?, [type-specific config])`

Creates a new CEDAR field of the requested kind: text, text-area, numeric,
temporal, radio, checkbox, single- or multi-select list, controlled-term, link,
email, phone, the `ext-*` identifier fields (ROR, ORCID, PFAS, RRID, PubMed,
NIH-grant-ID, DOI), attribute-value, and the static placeholders. For literal
fields, common configuration is accepted inline:

- numeric: `datatype`, `min_value`, `max_value`, `decimal_places`, `unit`
- temporal: `datatype`, `granularity`, `input_time_format`, `input_time_zone`
- text / text-area: `min_length`, `max_length`, `regex`

For fields whose shape needs structured sub-objects (controlled-term values,
inline radio/checkbox/list options, multi-instance configuration, default
values), author the field directly as YAML and pass it to whatever consumes it
(`add_field`, the `set_*` tools, …) — the threading tools accept YAML. Constraints
and default values can also be layered on via the `set_*_constraint` and `set_*_default_value`
tools. `id` is optional; omit it and a fresh `template-fields` IRI is
auto-minted (a field is a first-class, reusable CEDAR artifact, so a standalone
one gets an id like any other top-level artifact).

### `add_field(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`

Adds an existing field as a child of a template or element parent. The
per-add-site overrides set how the field appears in *this* parent — the key it
binds to, the label and description shown in the UI, whether it's
single-instance or multi-instance (with optional `minItems` / `maxItems`).
They're per-add-site because the same reusable field may be used differently in
different parents.

### `add_element(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`

Element variant of `add_field`: adds an existing element as a child of a
template or element parent. Same per-add-site overrides apply.

### `remove_child(parent_json, key)`

Removes a field or element child from a template or element parent by key.

### `set_class_constraint(field_json, class_iri, ontology_acronym, label, pref_label, value_type?)`

Pins a controlled-term field to a single ontology class. The input tuple
matches what `bioportal-term-mcp`'s `get_class` returns. `value_type` defaults
to `"class"` (a real ontology class) or `"value"` for permissible-value
entries.

### `set_ontology_constraint(field_json, ontology_iri, ontology_acronym, ontology_name)`

Scopes a controlled-term field's permissible values to all classes from a
named ontology. The input tuple matches `bioportal-term-mcp`'s `get_ontology`.

### `set_branch_constraint(field_json, ontology_name, ontology_acronym, branch_iri, branch_label, max_depth?)`

Scopes a controlled-term field to an ontology subtree rooted at a named class.
`max_depth` defaults to `0` (unbounded).

### `set_valueset_constraint(field_json, value_set_iri, vs_collection, name)`

Pins a controlled-term field to a curated value set hosted in BioPortal (e.g.
in `CEDARVS` or `HRAVS`).

All four constraint tools accept either an empty controlled-term field or a
plain text-field as input — the library only classifies a field as
controlled-term once it carries at least one constraint, so the two are wire-
indistinguishable until then.

### `set_default_value(field_json, value)`

Attaches a default value to a literal-valued field (text, text-area, numeric,
temporal, phone, email, radio, checkbox, list). The value type must match the
field's input type.

### `set_iri_default_value(field_json, iri)`

Attaches a default URI to an IRI-valued field (link, ROR, ORCID, PFAS, RRID,
PubMed, NIH-grant-ID, DOI). The schema-level default is a bare URI; if you want
a default that carries a human label too, set it on the instance side via
`set_iri_field_value`.

### `set_controlled_term_default_value(field_json, iri, label)`

Attaches a default class IRI + human label to a controlled-term field. The
field must already carry at least one `set_*_constraint` constraint; a plain
text-field is refused with a redirect to the constraint tools.

### `set_field_value(template_json, instance_json, field_path, value)`

Sets the value of a literal-valued field on an instance — text, numeric,
temporal, phone, email, radio, checkbox, list, or text-area. The value type
must match the schema's input type.

### `set_iri_field_value(template_json, instance_json, field_path, iri, label?)`

Sets the URI (and optional human label) of an IRI-valued field on an instance
— link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, or DOI. The label
populates `rdfs:label` alongside the URI and is typically supplied.

### `set_controlled_term_field_value(template_json, instance_json, field_path, iri, label, pref_label?)`

Sets the URI, human label, and preferred label of a controlled-term field on
an instance. The schema must declare the field as controlled-term (with at
least one `set_*_constraint` already attached).

#### Notes shared by the three instance-side `set_*_field_value` tools

`field_path` uses slash-separated nesting and bracketed indices for
multi-instance children: `address/street`, `addresses[2]/street`, `emails[0]`.
For multi-instance fields at the leaf, an index equal to the current list size
appends a new entry; any larger index errors.

`template_json` is required because the instance JSON loses field-type
information on round-trip — the schema is the source of truth for which kind
of field the value belongs to.

### `create_instance(template_json, name?, description?, is_based_on?, id?)`

Creates an instance from a template, ready to be populated with field values.
The returned YAML is **sparse** — it carries the instance identity (`@id`,
`name`, `is_based_on`) and only fields that hold a value, so a fresh instance is
essentially just its identity. Unset fields are reconstructed from the template
when JSON is produced (`validate_instance`, `instance_to_json`), so the instance
is still structurally complete. `is_based_on` defaults to the template's `@id`
when present; supply it explicitly only if the template has no `@id` (templates
from `create_template` / `template_to_json` now always carry a minted one). `id`
is the instance's own identity — optional, and auto-minted as a fresh
`template-instances` IRI when omitted; it is independent of `is_based_on`.

### `validate_instance(template_json, instance_json)`

Validates a template instance (YAML) against its template (YAML). Returns
`{"valid": true}` on success, or `{"valid": false, "errors": [...]}` with
diagnostics on failure.

### `validate_template` / `validate_element` / `validate_field` / `validate_artifact` `(artifact)`

Validate a **standalone** artifact against the CEDAR model schema — built for
checking artifacts obtained **from the wild** (e.g. pulled from cedar-server or a
colleague). Each takes a single `artifact` as JSON Schema or YAML (auto-detected);
JSON is validated **exactly as received** (no round-trip through the library, so the
verdict reflects the artifact itself), while YAML is read through the library first.
The report shape matches `validate_instance` — `{"valid": true}` or
`{"valid": false, "errors": [...]}`, returned as a successful tool call either way.

`validate_template` / `validate_element` / `validate_field` each validate the named
kind (and redirect with a helpful message if you hand them the wrong one).
`validate_artifact` **auto-detects** the kind from the artifact's `@type` and
dispatches — use it when you don't know whether you've got a template, element, or
field. (A template *instance* is detected but must go through `validate_instance`,
which also needs the template it's based on.)

### Export and import: `*_to_json` / `*_to_yaml`

Artifacts thread between tools as YAML. Two families bridge to and from the
canonical JSON Schema:

- **`template_to_json` / `element_to_json` / `field_to_json` / `instance_to_json`** —
  **export.** Take an artifact (YAML) and return the canonical CEDAR JSON Schema for
  cedar-server and other downstream consumers. The result is round-tripped through the
  library reader/renderer and validated (`CedarValidator`), so a non-error result is a
  guaranteed-valid artifact. If a top-level `id` is omitted, a fresh IRI is minted onto
  the result (nested children untouched). `instance_to_json` takes the instance's
  `template_json` as an optional argument: pass it to inflate the sparse instance back to a
  complete CEDAR JSON instance (every template field present); omit it to export only the
  fields the instance carries.
- **`template_to_yaml` / `element_to_yaml` / `field_to_yaml` / `instance_to_yaml`
  `(artifact, isCompact?)`** — **render as YAML.** Takes an artifact as YAML or JSON Schema
  (auto-detected) and emits YAML. `isCompact` is the only compaction control, so this is both
  how you **recompact** an expanded-YAML artifact for a lean display (`isCompact: true`, no JSON
  detour) and how you **import** an external JSON Schema artifact into the YAML loop. `isCompact`
  defaults to `true`; pass `false` for the expanded, provenance-preserving exchange form.

Example YAML an export tool accepts (and the create/add/set tools emit):

```yaml
type: template
name: Patient demographics
description: Minimal demographics template
version: 0.1.0
status: draft
modelVersion: 1.6.0
children:
  - key: patient_name
    type: text-field
    name: Patient name
    description: Free-text patient name
```

### `ping(message)`

Echoes `pong: <message>` back. Useful for verifying the MCP server is reachable
from a client. No library interaction.

| Input | Output |
|---|---|
| `{ "message": "hello" }` | `"pong: hello"` |

## Requirements

- Java 17 or newer
- [Maven](https://maven.apache.org/) 3.9 or newer
- A local install of `cedar-artifact-library` 2.8.1-SNAPSHOT (in `~/.m2/repository` or
  a reachable Maven repository). Tracks the library's `develop` branch; the library is
  not yet on Maven Central. Build the library locally with `mvn install` from a checkout
  of [metadatacenter/cedar-artifact-library](https://github.com/metadatacenter/cedar-artifact-library)
  on `develop`.

## Build

```bash
git clone https://github.com/metadatacenter/cedar-artifact-mcp.git
cd cedar-artifact-mcp
mvn package
```

The build produces two jars in `target/`:

- `cedar-artifact-mcp-<version>.jar` — the thin jar, no dependencies bundled.
- `cedar-artifact-mcp-<version>-all.jar` — an executable shaded jar with everything
  bundled. This is what MCP clients launch.

## Running

The server speaks MCP over stdio. Launch directly to confirm it starts:

```bash
java -jar target/cedar-artifact-mcp-<version>-all.jar
```

The server will sit waiting for JSON-RPC messages on stdin. `Ctrl-C` to exit.

To use it from an MCP client (Claude Code, Claude Desktop, etc.), register it in the
client's MCP configuration. For Claude Code, edit `~/.claude.json`:

```json
{
  "mcpServers": {
    "cedar-artifact": {
      "command": "/usr/bin/java",
      "args": [
        "-jar",
        "/absolute/path/to/cedar-artifact-mcp/target/cedar-artifact-mcp-0.1.0-SNAPSHOT-all.jar"
      ]
    }
  }
}
```

Notes:

- Use the absolute path to `java`. GUI clients don't inherit shell `PATH`.
- Restart the MCP client after editing the config; servers are launched once per
  session.

## Smoke test

Feed four JSON-RPC messages over stdio to confirm the server initializes, lists tools,
and responds to a `ping` call:

```bash
cat <<'EOF' | java -jar target/cedar-artifact-mcp-0.1.0-SNAPSHOT-all.jar
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{"message":"hello"}}}
EOF
```

You should see three JSON-RPC responses on stdout: server capabilities, the tool list,
and `pong: hello`.

## Development

```bash
mvn compile     # compile only
mvn test        # unit tests (surefire) — in-process, no subprocess
mvn package     # build the shaded jar
mvn verify      # full cycle: unit tests + package + end-to-end ITs (failsafe)
```

The test suite has two tiers, plus an opt-in real-world battery:

- **Unit tests** (`*Test.java`) drive tool handlers directly with synthetic requests.
  Fast, in-process, no subprocess. Validate the rendered output against the same
  `CedarValidator` the artifact library's own renderer tests use.
- **End-to-end ITs** (`*IT.java`, e.g. `EndToEndStdioIT`) spawn the shaded jar as a
  real subprocess, speak real JSON-RPC over real stdio, and validate the returned
  template again from the other side of the wire. This is the regression net for
  shading, classpath, stdio-transport, and tool-registration failures that
  in-process tests can't catch.
### Real-world coverage

The MCP's `EndToEndStdioIT` includes one case
(`server_compiles_controlled_term_yaml_end_to_end`) that drives a canonical
CEDAR YAML template — including a controlled-term constraint — through the
shaded jar over real stdio. That's enough at this layer to catch MCP-specific
regressions (transport, shading, tool registration).

The exhaustive real-world battery — every vendored CEDAR template
round-tripped through reader / renderer / validator — lives in
`cedar-artifact-library` as `TemplateBatteryYamlToJsonTest`,
`TemplateBatteryYamlRoundTripTest`, and `TemplateBatteryJsonRoundTripTest`.
That's the right home for it: the tests exercise the library's
reader/renderer/validator without any MCP wrapping, and the goldens are
derived from the library's own round-trip.

## License

BSD-2-Clause. See [license.txt](./license.txt).

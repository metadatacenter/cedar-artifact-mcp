# cedar-artifact-mcp

[CEDAR](https://metadatacenter.org/) — the Center for Expanded Data Annotation
and Retrieval, a Stanford BMIR project — builds tools for authoring and
applying metadata templates over scientific datasets. The metadata-template
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
calling LLM threads those tuples into this MCP's tools.

See [DESIGN.md](./DESIGN.md) for the architectural principles and
[ROADMAP.md](./ROADMAP.md) for what's planned.

## Example workflow

A typical authoring session looks like the following — natural-language prompts
the user gives the LLM, which the LLM translates into MCP tool calls. This
example exercises the structural and instance tools end-to-end; controlled-term
constraints are deliberately omitted (they're covered in a separate example set
that pairs the artifact MCP with a terminology MCP).

Each step shows the YAML the LLM is expected to display back after the matching
tool call.

**Create a template called Patient Study.**

```yaml
type: template
name: Patient Study
```

**Create a text field called Patient Name.**

```yaml
type: text-field
name: Patient Name
```

**Create a numeric field called Age with type `xsd:int`.**

```yaml
type: numeric-field
name: Age
datatype: xsd:int
```

**Set default value 42 on the Age field.**

```yaml
type: numeric-field
name: Age
datatype: xsd:int
default: 42
```

**Add Patient Name and Age to Patient Study.**

```yaml
type: template
name: Patient Study
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
    default: 42
```

**Create an element called Address with a text field Street.**

```yaml
type: element
name: Address
children:
  - key: Street
    type: text-field
    name: Street
```

**Add the Address element to Patient Study.**

```yaml
type: template
name: Patient Study
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
    default: 42
  - key: Address
    type: element
    name: Address
    children:
      - key: Street
        type: text-field
        name: Street
```

**Create an instance of Patient Study.**

```yaml
type: instance
name: Patient Study
description: Instance of Patient Study
isBasedOn: https://repo.metadatacenter.org/templates/patient-study
```

**Set Patient Name to Alice in the instance.**

```yaml
type: instance
name: Patient Study
description: Instance of Patient Study
isBasedOn: https://repo.metadatacenter.org/templates/patient-study
children:
  Patient Name:
    value: Alice
```

**Set Age to 30 in the instance.**

```yaml
type: instance
name: Patient Study
description: Instance of Patient Study
isBasedOn: https://repo.metadatacenter.org/templates/patient-study
children:
  Patient Name:
    value: Alice
  Age:
    datatype: xsd:int
    value: 30
```

## Tools

### `create_template(name, description?, version?)`

Creates a new, empty CEDAR template. The result is threaded into the
composition tools (`add_field`, `add_element`, `create_instance`, …) to build
something larger.

### `create_element(name, description?, version?)`

Creates a new, empty CEDAR element — a reusable sub-schema that can be embedded
inside templates or other elements.

### `create_field(name, type, description?, version?, [type-specific config])`

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
values), reach for `field_from_yaml` instead. Constraints and default values
can also be layered on via the `add_*_constraint` and `add_*_default_value`
tools.

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

### `add_class_constraint(field_json, class_iri, ontology_acronym, label, pref_label, value_type?)`

Pins a controlled-term field to a single ontology class. The input tuple
matches what `bioportal-term-mcp`'s `get_class` returns. `value_type` defaults
to `"class"` (a real ontology class) or `"value"` for permissible-value
entries.

### `add_ontology_constraint(field_json, ontology_iri, ontology_acronym, ontology_name)`

Scopes a controlled-term field's permissible values to all classes from a
named ontology. The input tuple matches `bioportal-term-mcp`'s `get_ontology`.

### `add_branch_constraint(field_json, ontology_name, ontology_acronym, branch_iri, branch_label, max_depth?)`

Scopes a controlled-term field to an ontology subtree rooted at a named class.
`max_depth` defaults to `0` (unbounded).

### `add_valueset_constraint(field_json, value_set_iri, vs_collection, name)`

Pins a controlled-term field to a curated value set hosted in BioPortal (e.g.
in `CEDARVS` or `HRAVS`).

All four constraint tools accept either an empty controlled-term field or a
plain text-field as input — the library only classifies a field as
controlled-term once it carries at least one constraint, so the two are wire-
indistinguishable until then.

### `create_instance(template_json, name?, description?, is_based_on?)`

Creates an empty (skeleton) instance from a template, ready to be populated
with field values. `is_based_on` defaults to the template's `@id` when
present; for freshly built templates without an `@id` it must be supplied
explicitly.

### `add_default_value(field_json, value)`

Attaches a default value to a literal-valued field (text, text-area, numeric,
temporal, phone, email, radio, checkbox, list). The value type must match the
field's input type.

### `add_iri_default_value(field_json, iri)`

Attaches a default URI to an IRI-valued field (link, ROR, ORCID, PFAS, RRID,
PubMed, NIH-grant-ID, DOI). The schema-level default is a bare URI; if you want
a default that carries a human label too, set it on the instance side via
`set_iri_field_value`.

### `add_controlled_term_default_value(field_json, iri, label)`

Attaches a default class IRI + human label to a controlled-term field. The
field must already carry at least one `add_*_constraint` constraint; a plain
text-field is refused with a redirect to the constraint tools.

### `instance_from_yaml(yaml)`

Compiles a template instance from YAML to its canonical JSON. Minimal instance
YAML needs `type: instance`, `name`, and `isBasedOn` (the template's URI);
field values live under a `children` map keyed by the template's child keys.

### `instance_to_yaml(json, isCompact?)`

Renders a template instance JSON back as YAML. `isCompact` defaults to `true`
(the lean authoring view); pass `false` to keep every field including
provenance.

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
least one `add_*_constraint` already attached).

#### Notes shared by the three `set_*` tools

`field_path` uses slash-separated nesting and bracketed indices for
multi-instance children: `address/street`, `addresses[2]/street`, `emails[0]`.
For multi-instance fields at the leaf, an index equal to the current list size
appends a new entry; any larger index errors.

`template_json` is required because the instance JSON loses field-type
information on round-trip — the schema is the source of truth for which kind
of field the value belongs to.

### `validate_instance(template_json, instance_json)`

Validates a template instance against its template. Returns
`{"valid": true}` on success, or `{"valid": false, "errors": [...]}` with
diagnostics on failure.

### `template_from_yaml(yaml)`

**The headline authoring tool.** Compiles a CEDAR template described in the
artifact library's compact YAML into canonical CEDAR JSON. A non-error result
is a guaranteed-valid template. Example YAML input:

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

### `element_from_yaml(yaml)`

Element variant of `template_from_yaml`: compiles a CEDAR element described in
YAML into canonical JSON. Use this when authoring a reusable element that will
later be embedded in a template.

### `field_from_yaml(yaml)`

Field variant of `template_from_yaml`: compiles a CEDAR field described in
YAML into canonical JSON. Reach for this (vs. `create_field`) when the field
needs structured sub-objects — controlled-term values, inline
radio/checkbox/list options, default values, multi-instance configuration.

### `template_to_yaml(json, isCompact?)`

Renders a CEDAR template JSON back as YAML. `isCompact` defaults to `true` —
the lean, LLM-friendly authoring form, with provenance, status, version, and
`modelVersion` omitted. Pass `false` to keep every field for archival or
round-trip diffing.

### `element_to_yaml(json, isCompact?)`

Element variant of `template_to_yaml`. Same `isCompact` semantics.

### `field_to_yaml(json, isCompact?)`

Field variant of `template_to_yaml`. Same `isCompact` semantics.

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

The exhaustive real-world battery — every published HuBMAP template
round-tripped through reader / renderer / validator — lives in
`cedar-artifact-library` as `HubmapTemplatesRoundTripTest`. That's the right
home for it: the test exercises the library's reader/renderer/validator without
any MCP wrapping, and the goldens are derived from the library's own
round-trip. See the library's `develop` branch.

## License

BSD-2-Clause. See [license.txt](./license.txt).

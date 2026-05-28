# cedar-artifact-mcp

A [Model Context Protocol](https://modelcontextprotocol.io/) server that exposes the
[CEDAR artifact library](https://github.com/metadatacenter/cedar-artifact-library) —
its builders, readers, renderers, and validators — as composable tools an LLM can call.

The server is the model-construction half of a metadata-template pipeline: it knows
how to assemble CEDAR templates, elements, fields, value constraints, and instances,
but it does not perform terminology lookups, talk to a CEDAR server, or do any other
I/O. Terminology MCPs (e.g.
[`bioportal-term-mcp`](https://github.com/metadatacenter/bioportal-term-mcp)) supply
the IRI/acronym/name tuples that controlled-term constraints need; the calling LLM
threads those tuples into this MCP's tools.

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

### `ping(message)`

Echoes `pong: <message>` back. Useful for verifying the MCP server is reachable from
a client. No library interaction.

| Input | Output |
|---|---|
| `{ "message": "hello" }` | `"pong: hello"` |

### `create_template(name, description?, version?)`

Builds an empty CEDAR template schema artifact and returns it as JSON. Validated
with `CedarValidator.validateTemplate` before returning. The returned JSON is
threaded into follow-up tools (`add_field`, `add_element`, `create_instance`, …)
to compose a larger template.

### `create_element(name, description?, version?)`

Element variant of `create_template`. Returns an empty CEDAR element schema
artifact as JSON, validated with `CedarValidator.validateTemplateElement`.
Elements are first-class CEDAR artifacts, on equal footing with templates, and
can be embedded inside other templates or elements.

### `create_field(name, type, description?, version?, [type-specific config])`

Builds a CEDAR field schema artifact of the requested kebab-case `type` — the
same vocabulary `field_from_yaml` accepts (`text-field`, `controlled-term-field`,
`numeric-field`, `temporal-field`, `radio-field`, `checkbox-field`, the list,
static, and identifier variants — see the tool's input schema for the complete
enum). Returns JSON validated with `CedarValidator.validateTemplateField`.

For common literal-field cases the tool accepts type-specific configuration
inline:

- numeric: `datatype`, `min_value`, `max_value`, `decimal_places`, `unit`
- temporal: `datatype`, `granularity`, `input_time_format`, `input_time_zone`
- text / text-area: `min_length`, `max_length`, `regex`

Passing a param that doesn't apply to the chosen field type is rejected with a
clear error. For shapes that need structured sub-objects (controlled-term
values, radio/checkbox/list inline values, multi-instance bounds, default
values) use `field_from_yaml` instead; constraints and default values can also
be layered on via the `add_*_constraint` / `add_*_default_value` tools.

### `add_field(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`

Adds an existing CEDAR field (typically produced by `create_field` or
`field_from_yaml`) as a child of a CEDAR template or element. Parent kind is
inferred from its `@type` URI; the result is re-validated with the matching
CedarValidator method.

The optional per-add-site overrides:

- `key` — JSON Schema property key in the parent; falls back to child's
  `schema:name`. The library rejects duplicate keys, so supply an explicit `key`
  when adding two children with the same name.
- `name` — propertyLabel for the parent's `_ui` block; falls back to child's
  `schema:name`.
- `description` — propertyDescription for the parent's `_ui` block; falls back
  to child's `schema:description`.
- `isMultiInstance` (default `false`) — whether the child appears as an array
  (multi) or a single object in instances of the parent.
- `minItems` / `maxItems` — bounds on the array length when `isMultiInstance` is
  true.

All five are per-add-site because the same reusable child may be used
differently in different parents (single-instance in one, bounded multi-instance
in another, with distinct labels each time).

### `add_element(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`

Element variant of `add_field`: adds an existing CEDAR element (from
`create_element` or `element_from_yaml`) as a child of a template or element.
Same per-add-site overrides apply. The compose workflow is two-step by design
— build the child first, then graft it onto the parent — to keep the MCP API
surface small.

### `remove_child(parent_json, key)`

Removes a field or element child from a CEDAR template or element parent. Auto-detects
whether the key names a field or element child by looking it up in the parent's
`fieldSchemas` / `elementSchemas`; the parent's `_ui` `order`, `propertyLabels`, and
`propertyDescriptions` entries are removed in lockstep. Returns the updated parent
JSON, re-validated with CedarValidator.

### `add_class_constraint(field_json, class_iri, ontology_acronym, label, pref_label, value_type?)`

Pins a controlled-term field to a single ontology class. The canonical input tuple
matches what `bioportal-term-mcp`'s `get_class` returns. `value_type` is `"class"` by
default (a real ontology class) or `"value"` for permissible-value entries.

### `add_ontology_constraint(field_json, ontology_iri, ontology_acronym, ontology_name)`

Scopes a controlled-term field's permissible values to all classes from a named
ontology. The canonical input tuple matches `bioportal-term-mcp`'s `get_ontology`.

### `add_branch_constraint(field_json, ontology_name, ontology_acronym, branch_iri, branch_label, max_depth?)`

Scopes a controlled-term field to a subtree rooted at a named class. `max_depth`
defaults to `0` (the library's convention for unbounded depth).

### `add_valueset_constraint(field_json, value_set_iri, vs_collection, name)`

Pins a controlled-term field to a curated value set hosted in BioPortal. Value sets
live in special "value-set collection" ontologies (e.g. `CEDARVS`, `HRAVS`); the
`vs_collection` arg names that collection.

All four constraint tools accept any TEXTFIELD-shape field (text-field or
controlled-term-field) and produce a controlled-term-field with the new constraint
attached. The library's reader only classifies a TEXTFIELD as controlled-term once it
carries a constraint, so an "empty controlled-term-field" and a "text-field" are JSON-
indistinguishable on the wire — both are valid inputs here.

### `create_instance(template_json, name?, description?, is_based_on?)`

Builds an empty (skeleton) CEDAR template instance from a template JSON. Walks the
template's children and creates one empty `FieldInstance` per non-static,
non-attribute-value field; multi-instance children start as empty arrays; nested
elements are recursively populated. The `@context` mappings are populated from the
template's child property URIs so the result passes `validate_instance` straight away.

`is_based_on` defaults to the template's `@id` when present. For freshly built
templates without an `@id` (e.g. just out of `create_template`) the caller must supply
`is_based_on` explicitly.

### `add_default_value(field_json, value)`

Attaches a schema-level default value to a literal-valued CEDAR field (text,
text-area, numeric, temporal, phone, email, radio, checkbox, list). The value
type must match the field's input type. Returns the updated field JSON,
re-validated with CedarValidator.

### `add_iri_default_value(field_json, iri)`

Attaches a default URI to an IRI-valued CEDAR field (link, ROR, ORCID, PFAS,
RRID, PubMed, NIH-grant-ID, DOI). The library's schema-level IRI default is a
bare URI with no label — if a labelled default is needed, set it on the instance
side via `set_iri_field_value`.

### `add_controlled_term_default_value(field_json, iri, label)`

Attaches a default class IRI + human label to a CEDAR controlled-term field.
Requires the field to already be classified as a ControlledTermField (carrying
at least one `add_*_constraint` constraint); a plain text-field is refused with
a redirect to the constraint tools.

### `instance_from_yaml(yaml)`

Compiles a CEDAR template instance from YAML to its canonical JSON. Minimal
instance YAML needs `type: instance`, `name`, and `isBasedOn` (the template's
URI); per-field values live under a `children` map keyed by the schema's
property keys.

### `instance_to_yaml(json, isCompact?)`

Reverse direction of `instance_from_yaml`: renders a canonical CEDAR template
instance JSON as YAML. `isCompact` defaults to `true` (drops provenance metadata
and elides empty fields for the LLM-friendly authoring view); pass `false` to
keep every field the renderer can emit.

### `set_field_value(template_json, instance_json, field_path, value)`

Sets the `@value` of a literal-valued field instance (text, numeric, temporal,
phone, email, radio, checkbox, list, text-area) at a slash-separated
`field_path`. Value type must match the schema's input type. Returns the
updated instance JSON.

### `set_iri_field_value(template_json, instance_json, field_path, iri, label?)`

Sets the `@id` of an IRI-valued field instance (link, ROR, ORCID, PFAS, RRID,
PubMed, NIH-grant-ID, DOI) at a slash-separated `field_path`. The optional
`label` populates `rdfs:label` alongside the `@id` and is typically supplied
(the terminology MCP returns it).

### `set_controlled_term_field_value(template_json, instance_json, field_path, iri, label, pref_label?)`

Sets the IRI + `rdfs:label` + `skos:prefLabel` of a controlled-term field
instance at a slash-separated `field_path`. Both `iri` and `label` are required;
`pref_label` defaults to `label` when omitted. The schema must declare the
field as controlled-term (carrying at least one class/ontology/branch/value-set
constraint) — see the note on the wire collision in the constraint docs above.

#### Notes shared by the three `set_*` tools

`field_path` uses slash-separated nesting and bracketed indices for
multi-instance children: `address/street`, `addresses[2]/street`, `emails[0]`.
For multi-instance fields at the leaf, an index equal to the current list size
appends (extends the list by one); any larger index errors. Multi-instance
element indices walking through intermediate steps must already exist.

The `template_json` argument is required because the instance JSON loses field
type info on round-trip — the schema is the source of truth for which kind of
`FieldInstance` to build.

### `validate_instance(template_json, instance_json)`

Validates a template instance against its template via
`CedarValidator.validateTemplateInstance`. Returns a structured report:
`{"valid": true}` on success, or `{"valid": false, "errors": [...]}` with the
validator's diagnostics on failure.

### `template_from_yaml(yaml)`

**The headline authoring tool.** Takes a CEDAR template described in the artifact
library's YAML format (compact, hierarchical, LLM-friendly) and returns the canonical
CEDAR JSON Schema. The four-stage pipeline is:

1. SnakeYAML parses the input text to a map.
2. `YamlArtifactReader` reads the map into the in-memory model.
3. `JsonArtifactRenderer` renders the model to JSON Schema.
4. `CedarValidator` validates the JSON Schema before it leaves the tool.

A non-error result is a guaranteed-valid CEDAR template. Example YAML input:

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

Element variant of `template_from_yaml`. Same four-stage pipeline; validates
with `validateTemplateElement`. Use this when authoring a reusable element that
will later be embedded in a template by other tooling.

### `field_from_yaml(yaml)`

Field variant of `template_from_yaml`. Same four-stage pipeline; validates with
`validateTemplateField`. Use this when authoring a standalone field with
structured sub-objects (controlled-term values, inline radio/checkbox/list
values, default values, multi-instance configuration) that the more compact
`create_field` interface doesn't reach.

### `template_to_yaml(json, isCompact?)`

Reverse direction of `template_from_yaml`: takes a CEDAR template JSON Schema
and returns the artifact library's YAML serialization. `isCompact` defaults to
`true`:

- `isCompact: true` (default) — the lean, LLM-friendly authoring form. Provenance
  fields, status, version, and `modelVersion` are all omitted. The matching
  `*_from_yaml` tool runs the library reader in compact mode, which defaults an
  absent `modelVersion` to the canonical value, so compact YAML round-trips
  cleanly. Best for showing an LLM an artifact it should edit.
- `isCompact: false` — every field the renderer can emit. Suitable for archival
  and round-trip diffing where provenance and version metadata need to survive.

### `element_to_yaml(json, isCompact?)`

Element variant of `template_to_yaml`. Same `isCompact` semantics; renders a
CEDAR element JSON Schema as YAML.

### `field_to_yaml(json, isCompact?)`

Field variant of `template_to_yaml`. Same `isCompact` semantics; renders a CEDAR
field JSON Schema as YAML.

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

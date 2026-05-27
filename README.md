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

## Status

Nineteen tools: `ping`, the three empty-shell builders (`create_template`,
`create_element`, `create_field`), the headline authoring tool
`template_from_yaml` and its element/field variants (`element_from_yaml`,
`field_from_yaml`), the matching reverse-direction tools (`template_to_yaml`,
`element_to_yaml`, `field_to_yaml`), the two incremental builders
(`add_field`, `add_element`), the four value-constraint tools
(`add_class_constraint`, `add_ontology_constraint`, `add_branch_constraint`,
`add_valueset_constraint`), and the instance trio (`instance_from_yaml`,
`instance_to_yaml`, `validate_instance`). The roadmap covers skeleton creation
from a template and value-setter tools.

## Tools

### `ping(message)`

Echoes `pong: <message>` back. Useful for verifying the MCP server is reachable from
a client. No library interaction.

| Input | Output |
|---|---|
| `{ "message": "hello" }` | `"pong: hello"` |

### `create_template(name, description?, version?)` / `create_element(name, description?, version?)` / `create_field(name, type, description?, version?)`

The three empty-shell builders. Each returns a CEDAR JSON Schema artifact of the
matching kind, validated with `CedarValidator` before returning. Elements and fields
are first-class CEDAR artifacts, on equal footing with templates.

`create_field` takes a `type` discriminator — the same kebab-case vocabulary
`field_from_yaml` accepts (`text-field`, `controlled-term-field`, `numeric-field`,
`temporal-field`, `radio-field`, `checkbox-field`, the list, list-extension, static,
and identifier variants — see the tool's input schema for the complete enum).
`numeric-field` and `temporal-field` get sensible defaults (`xsd:decimal`,
`xsd:date` with day granularity) since the library refuses to build either
without those.

These tools are lower-level than the `*_from_yaml` family; useful when programmatically
composing artifacts from non-YAML inputs, or as starting points for the eventual
incremental builder / value-constraint tools.

### `add_field(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)` / `add_element(parent_json, child_json, key?, name?, description?, isMultiInstance?, minItems?, maxItems?)`

Adds an existing child (typically produced by `create_field` / `create_element` or
the matching `*_from_yaml` tool) as a child of a CEDAR template or element. Parent
kind is inferred from its `@type` URI; the result is re-validated with the matching
CedarValidator method.

The optional per-add-site overrides:

- `key` — JSON Schema property key in the parent; falls back to child's `schema:name`.
  The library rejects duplicate keys, so supply an explicit `key` when adding two
  children with the same name.
- `name` — propertyLabel for the parent's `_ui` block; falls back to child's `schema:name`.
- `description` — propertyDescription for the parent's `_ui` block; falls back to child's `schema:description`.
- `isMultiInstance` (default `false`) — whether the child appears as an array (multi)
  or a single object in instances of the parent.
- `minItems` / `maxItems` — bounds on the array length when `isMultiInstance` is true.

All five are per-add-site because the same reusable child may be used differently in
different parents (single-instance in one, bounded multi-instance in another, with
distinct labels each time).

The compose workflow is two-step by design — build the child first, then graft it
onto the parent — to keep the MCP API surface small (the library does allow
on-the-fly creation, but the MCP keeps that one obvious path).

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

### `instance_from_yaml(yaml)` / `instance_to_yaml(json, isCompact?)`

Compile a CEDAR template instance from YAML to its canonical JSON, or back. Same shape
as the schema-side `*_from_yaml` / `*_to_yaml` tools. Minimal instance YAML needs
`type: instance`, `name`, and `isBasedOn` (the template's URI); per-field values live
under a `children` map keyed by the schema's property keys.

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

### `element_from_yaml(yaml)` / `field_from_yaml(yaml)`

Element and field variants of `template_from_yaml`. Same four-stage pipeline as the
template tool; validate with `validateTemplateElement` and `validateTemplateField`
respectively. Use these when authoring a reusable element or a standalone field that
will later be embedded in a template by other tooling.

### `template_to_yaml(json, isCompact?)` / `element_to_yaml(json, isCompact?)` / `field_to_yaml(json, isCompact?)`

Reverse direction of the matching `*_from_yaml` tools: each takes a CEDAR JSON Schema
artifact and returns the artifact library's YAML serialization. The `isCompact` boolean
selects between the two forms the `YamlArtifactRenderer` supports (default `true`):

- `isCompact: true` (default) — the lean, LLM-friendly authoring form. Provenance
  fields, status, version, and `modelVersion` are all omitted. The matching
  `*_from_yaml` tools run the library reader in compact mode, which defaults an
  absent `modelVersion` to the canonical value, so compact YAML round-trips
  cleanly. Best for showing an LLM an artifact it should edit.
- `isCompact: false` — every field the renderer can emit. Suitable for archival and
  round-trip diffing where provenance and version metadata need to survive.

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

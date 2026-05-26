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

Three tools: `ping`, `create_template`, and the headline authoring tool
`template_from_yaml`. The roadmap covers the element/field/instance variants and
the JSON-Schema-to-YAML reverse direction.

## Tools

### `ping(message)`

Echoes `pong: <message>` back. Useful for verifying the MCP server is reachable from
a client. No library interaction.

| Input | Output |
|---|---|
| `{ "message": "hello" }` | `"pong: hello"` |

### `create_template(name, description?, version?)`

Builds an empty CEDAR template schema artifact and returns it as JSON Schema. Validates
with `CedarValidator` before returning. Lower-level than `template_from_yaml`; useful
when programmatically composing templates from non-YAML inputs.

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
- **Real-world battery** (`HubmapTemplatesIT`) runs `template_from_yaml` against
  44 vendored golden YAML fixtures derived from the
  [HuBMAP template library](https://github.com/hubmapconsortium/dataset-metadata-spreadsheet)
  and asserts each compiles to a `CedarValidator`-passing JSON Schema. The
  fixtures under `src/test/resources/hubmap-golden/` are not the originals — they
  are regenerated from the paired JSON Schemas via the artifact library's own
  reader+renderer, so they are canonical CEDAR YAML by construction.
  Failures are reported per template name. Always-on; no system property gating.

### Regenerating the HuBMAP goldens

`GoldenYamlGenerator` is a one-shot utility that reads a directory of CEDAR JSON
Schemas, round-trips each through `JsonArtifactReader` and `YamlArtifactRenderer`,
and writes the result as YAML. Use it when the artifact library's YAML format
changes or when adding templates to the battery:

```bash
mvn test-compile exec:java \
    -Dexec.classpathScope=test \
    -Dexec.mainClass=org.metadatacenter.artifacts.mcp.GoldenYamlGenerator \
    -Dexec.args="/path/to/source-json-dir src/test/resources/hubmap-golden"
```

## License

BSD-2-Clause. See [license.txt](./license.txt).

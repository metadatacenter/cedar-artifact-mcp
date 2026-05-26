# Design

This document captures the principles that govern what belongs in this MCP server and
what does not. Read it before adding a tool, an input field, or any feature that the
existing `ping` example doesn't already establish a precedent for.

## Principle 1 — One library, no orchestration

This MCP wraps exactly one Java library: [`cedar-artifact-library`](https://github.com/metadatacenter/cedar-artifact-library).
It exposes the library's builders, readers, renderers, and validators as composable MCP
tools.

It does *not*:

- talk to BioPortal, OLS, or any other terminology service
- talk to a CEDAR server, a database, or any HTTP endpoint
- decide which fields a template should contain
- merge tuples from multiple sources

Those are orchestration concerns. They belong in the calling LLM (which can chain this
MCP with terminology MCPs like `bioportal-term-mcp`) or in domain-specific tooling built
on top.

## Principle 2 — Upstream-agnostic tool surface

A tool that takes a controlled-term constraint must accept the canonical fields the
library needs — an IRI, an acronym, a name — without committing to where those came
from. The same tool must work whether the caller pulled the tuple from BioPortal, an OLS
MCP, a local cache, or hand-typed it.

Specifically: do not name a parameter `bioportal_class_iri` or document a parameter as
"the IRI returned by `bioportal-term-mcp`". The tool's contract is with the artifact
library, not with any particular upstream.

## Principle 3 — Model first, I/O second

The artifact library models templates, elements, fields, value constraints, and
instances as in-memory Java objects with strongly-typed builders. That model is the
unit of work this MCP exposes. Persistence (writing to disk, posting to a server) and
foreign-format export (Excel, REDCap, etc.) are out of scope and belong in separate
MCPs.

For now the server is stateless — every tool call takes and returns JSON, with the
caller threading any intermediate state. If we later need cross-call session state
(e.g. an in-progress template handle), that's a deliberate design decision, not a
default.

## Principle 4 — Schema is the LLM's documentation

The MCP tool list and JSON input schemas are the only documentation the calling LLM
sees. Every tool needs:

- a clear, action-oriented `description` (one sentence on what the tool does and the
  canonical use case)
- a JSON input schema with `description` set on every property
- a tight `required` list

A tool whose behavior depends on a missing field, an undocumented enum, or a contextual
default is broken from the LLM's perspective even if it works for a human caller.

## Principle 5 — Errors are content

The artifact library throws checked and unchecked exceptions for invalid input
(missing required field, wrong constraint type for a field type, malformed value
constraint). Surface these as `CallToolResult` with `isError=true` and a textual
message — do not propagate them as MCP protocol errors. The LLM can read the message
and retry; an MCP protocol error truncates the conversation.

## Principle 6 — Validate before returning

Every tool that renders a CEDAR schema artifact (template, element, field, or
instance) must run the rendered JSON through `CedarValidator` — the same validator
the artifact library's own renderer tests use — before returning it. If validation
fails, return an error result with the validator's diagnostics, not the invalid JSON.

This is the contract the LLM relies on: a non-error result means the validator
accepts the artifact. It also surfaces library regressions immediately rather than
shipping subtly-wrong JSON downstream.

See `CreateTemplateTool.handler` for the canonical pattern.

## Principle 7 — Stay strict; fix the input

The library's YAML reader is the source of truth for what counts as valid CEDAR
YAML — it requires `modelVersion: 1.6.0`, requires string-typed values where it
expects strings, and so on. The MCP does not paper over deviations from that
contract with defaults injection or silent type coercion. If a YAML input the LLM
authored doesn't satisfy the reader, the failure surfaces verbatim and the author
(LLM or human) fixes the input.

YAML authored against an older library version should be regenerated against the
current library, not accommodated downstream. The library ships a regeneration
utility (`GoldenYamlGenerator`) that round-trips paired JSON Schemas through its
own reader and renderer to produce canonical YAML; see the library's
`HubmapTemplatesRoundTripTest` for the corresponding real-world coverage.

The strict policy keeps the MCP a thin transcoder over the library and forces YAML
authoring drift to surface as a library-version problem rather than a tool-leniency
problem.

## Principle 8 — Test the MCP, not the library

When a candidate test exercises the artifact library's reader/renderer/validator
through five lines of MCP wrapping, write it in the library, not here. The MCP
keeps coverage of MCP-specific concerns:

- Handler behavior (input parsing, error envelope, defaults in the JSON-RPC layer).
- Tool registration and input schemas (`tools/list` exposes them correctly).
- Stdio transport, JSON-RPC framing, session lifecycle.
- Shading (the executable jar wires up correctly, no classpath skew at runtime).

Real-world inputs through the MCP are covered by *one* representative case in
`EndToEndStdioIT`, sized to catch transport / shading / registration regressions
without redundantly testing what the library tests already cover. The exhaustive
real-world battery lives in `cedar-artifact-library` as
`HubmapTemplatesRoundTripTest`, where it tests reader/renderer/validator directly.

## Adding a new tool

1. Decide which library operation the tool wraps. If the operation isn't already
   covered by `cedar-artifact-library`, stop — fix the library first, then expose it.
2. Pick a tool name. Convention: `<verb>_<noun>` in snake_case, where the verb names
   the library operation (`create_template`, `add_field`, `read_template`, `render_json`).
3. Write a static factory `<name>Tool()` that returns a `McpSchema.Tool` with a typed
   JSON input schema.
4. Write a static handler `<name>Handler(exchange, request)` that pulls arguments out
   of `request.arguments()`, calls the library, and returns a `CallToolResult`.
5. Register on the server via `.toolCall(<name>Tool(), ArtifactMcpServer::<name>Handler)`.
6. Add a unit test (`*Test.java`, run by surefire in `mvn test`) that exercises the
   handler directly with synthetic `CallToolRequest`s. Validate the rendered JSON
   against `CedarValidator` in the happy-path case.
7. Add an end-to-end integration test in `EndToEndStdioIT` (run by failsafe in
   `mvn verify`) that drives a real subprocess of the shaded jar over real stdio.
   In-process tests don't catch shading, classpath layering, stdio-transport, or
   tool-registration failures; the IT does. The Jackson 2.x / 3.x classpath skew
   the scaffold hit during bootstrap is exactly the class of bug this layer is
   for.

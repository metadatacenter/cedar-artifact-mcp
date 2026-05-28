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

## Principle 4 — The tool surface is the LLM's documentation

The calling LLM only sees what MCP's `tools/list` returns: each tool's name, its
top-level `description`, and a `description` on each input parameter. Nothing else
from this repo, the artifact library, or any out-of-band documentation reaches the
LLM at call time. Everything the LLM needs to know about how to invoke a tool
correctly must therefore live in those surfaces.

A tool whose correct usage depends on a fact the LLM can't see — an undocumented
enum value, a contextual default a human contributor takes for granted, a hidden
coupling between two parameters — is broken from the LLM's perspective even if it
works for a human caller. Every tool needs:

- a clear, action-oriented top-level `description` (one sentence on what the tool
  does and the canonical use case)
- a `description` on every input parameter, listing the full vocabulary of values
  where there's a fixed set, units where applicable, and references to companion
  tools where there's a partner workflow
- a tight `required` list

Naming convention to avoid: this principle is about the **MCP tool-input-schema
mechanism** (which MCP defines using JSON Schema syntax). That is a different
thing from **CEDAR's JSON Schema serialization** of templates/elements/fields/
instances — one of several artifact serializations alongside YAML, spreadsheets,
UBKG, and others, none of which the tool surface privileges over any other.

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

## Principle 7 — Reader is the contract; lean on its compact mode

The library's YAML reader is the source of truth for what counts as valid CEDAR
YAML. The MCP does not add its own coercions or defaults *on top of* the reader.

The MCP instantiates the reader in **compact mode** (`new YamlArtifactReader(true)`)
so it accepts the same compact YAML form that `template_to_yaml` emits — modelVersion
absent is treated as the canonical model version. A *present-but-wrong* modelVersion
is still rejected by the reader: defaulting in compact mode covers absence only, not
silent stale-version acceptance.

YAML authored against an older library version should be regenerated against the
current library, not accommodated downstream. The library ships a regeneration
utility (`GoldenYamlGenerator`) that round-trips paired JSON Schemas through its
own reader and renderer to produce canonical YAML; see the library's
`HubmapTemplatesRoundTripTest` for the corresponding real-world coverage.

The shape of the contract — strict on values, lenient on absence in compact mode —
keeps the MCP a thin transcoder over the library and lets compact YAML flow
freely through the authoring loop.

## Principle 8 — YAML is the human-facing form; JSON Schema is wire format

CEDAR has two on-the-wire serializations for templates, elements, and fields: the
canonical **JSON Schema** (what cedar-server and every downstream CEDAR tool
consumes) and the compact **YAML** the artifact library reader/renderer pair was
built around. They carry the same information, but they serve different audiences:

- **JSON Schema** is verbose, dense with `_ui`, `@context`, `xsd` plumbing, and
  IRI-laden boilerplate. It exists so downstream services have an unambiguous
  schema to validate against. Humans do not read this for fun.
- **YAML** is the compact authoring form. It collapses the boilerplate into a
  handful of well-named keys (`type`, `name`, `children`, `configuration`,
  `values`), and a typical template fits comfortably on one screen.

The MCP's job-to-be-done — letting an LLM author and edit CEDAR artifacts with a
human in the loop — fits YAML, not JSON Schema. The tool surface reflects this:

- **JSON Schema is the threading currency between tool calls.** Every artifact
  tool that produces a template/element/field/instance returns JSON Schema so
  the caller can pipe it straight back into `add_field`, `add_element`,
  `validate_instance`, etc., without a re-parse step.
- **YAML is the display form for the user.** Every tool that returns JSON
  Schema documents in its description that the caller should round-trip through
  the matching `*_to_yaml` tool before showing the result. The
  `YAML_PREFERRED_DISPLAY_NUDGE` constant in `YamlVocabulary` is appended to
  each such description so the policy lives in one place.
- **YAML is also the preferred input form.** The four `*_from_yaml` tools take a
  human-edited YAML document and emit JSON Schema. The MCP guides authoring
  toward this path; JSON Schema input exists for round-tripping completeness,
  not as the encouraged authoring surface.

A consequence: the LLM should only show JSON Schema when the user explicitly
asks for it. JSON Schema in chat is noise; YAML in chat is signal. This
principle is the reason `YAML_PREFERRED_DISPLAY_NUDGE` exists at all — the only
documentation the LLM ever sees is the tool description (Principle 4), so the
display preference has to live there too.

## Principle 9 — Test the MCP, not the library

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

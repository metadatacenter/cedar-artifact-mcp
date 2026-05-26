# For Claude (or any new contributor)

Start with these, in order:

1. **[README.md](./README.md)** — what this project is, how to build and run it, the
   tool inventory and current status.
2. **[DESIGN.md](./DESIGN.md)** — the architectural principles. Read this *before*
   adding a tool, or you'll be tempted to put orchestration logic in the server that
   belongs in the calling LLM.
3. **[ROADMAP.md](./ROADMAP.md)** — what's done, what's next.

After those three, the code is self-explanatory. Patterns to mirror:

- Each tool: a static `Tool` factory that builds the `McpSchema.Tool` (name, title,
  description, input JSON schema) and a static handler with the
  `(McpSyncServerExchange, CallToolRequest) -> CallToolResult` signature. Register both
  on the `McpServer.sync(...)` spec via `.toolCall(tool, handler)`.
- Tool descriptions are the LLM's only documentation. Be concrete about inputs, outputs,
  and the canonical use case.
- Tool docstrings stay domain-neutral with respect to upstream — they should not assume
  that a tuple came from BioPortal. The CEDAR artifact library is the only thing this
  MCP commits to.
- See `pingTool()` / `pingHandler()` in `ArtifactMcpServer.java` for the minimum-viable
  pattern. Builder tools will follow the same shape, just with library calls in the
  handler.
- Schema-rendering tools must validate before returning — see DESIGN.md Principle 6
  and `CreateTemplateTool.handler` for the canonical pattern.

## Build conventions

- Java 17 source/target.
- `mvn package` produces an executable shaded jar at
  `target/cedar-artifact-mcp-<version>-all.jar`.
- The shade plugin needs explicit filters to strip Jackson 2.x annotation classes
  bundled inside `cedar-model-library` and `cedar-model-validation-library` — those
  shadow the Jackson 3.x annotations that the MCP SDK requires. See `pom.xml` for the
  current filter set; do not remove it.

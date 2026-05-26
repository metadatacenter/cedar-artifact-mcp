# Roadmap

This file tracks what's built, what's planned, and what's deliberately out of scope.

## Done

- Maven scaffold with the official MCP Java SDK (`io.modelcontextprotocol.sdk:mcp:1.1.3`)
  and `cedar-artifact-library:2.8.0`.
- Stdio transport server with a diagnostic `ping` tool.
- Shaded executable jar build (`mvn package` → `target/cedar-artifact-mcp-<v>-all.jar`).
- Jackson 2.x / Jackson 3.x classpath conflict resolved via explicit shade filters.

## Next — builder tools

In rough priority order. Each tool needs schema, handler, and a test.

### Template / element / field skeletons

- `create_template(name, description?, version?)` → returns a JSON handle for an
  in-progress template.
- `create_element(name, description?, version?)` → element handle.
- `add_field(parent_handle, field_type, name, description?, required?)` — one tool with
  a `field_type` enum discriminator covering all the field types in the library
  (text, numeric, controlled term, link, attribute-value, etc.). Discriminator drives
  the rest of the schema.
- `add_element_to_parent(parent_handle, child_handle, name)`.

### Value constraints

One tool per constraint kind. The canonical tuple inputs match what the artifact
library expects; they intentionally do not assume any particular upstream provided them.

- `add_ontology_constraint(field_handle, ontology_iri, acronym, name)`.
- `add_branch_constraint(field_handle, ontology_iri, acronym, name, branch_iri, branch_label)`.
- `add_class_constraint(field_handle, class_iri, ontology_acronym, label, source?)`.
- `add_valueset_constraint(field_handle, value_set_iri, vs_collection, name, num_terms?)`.
- `add_default_value(field_handle, value)`.

### Instances

- `create_instance(template_handle)`.
- `set_field_value(instance_handle, field_path, value)`.
- `set_controlled_term_field(instance_handle, field_path, class_iri, label)`.

### Readers / renderers

- `read_template_json(json_text)` → template handle.
- `read_instance_json(json_text)` → instance handle.
- `render_template_json(template_handle)`.
- `render_instance_json(instance_handle)`.
- YAML variants once the YAML round-trip work in the library lands.

### Validators

- `validate_instance(template_handle, instance_handle)` → validation report.

## Out of scope

The following belong in other MCPs (or do not belong in any MCP at all):

- Terminology lookups — use `bioportal-term-mcp` (or a future OLS-backed equivalent).
- Talking to a CEDAR repository server (workspace creation, template publishing).
- Excel, REDCap, CDISC, or other foreign-format exports.
- Sample / dataset generation against templates.

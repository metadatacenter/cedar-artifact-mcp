package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code remove_constraint} — the inverse of the {@code set_*_constraint}
 * family, addressed by the IRI a constraint points at, kind-blind. Removing the last
 * constraint deliberately yields a text-field-shaped field (the wire collision), and is
 * refused while a controlled-term default would be orphaned.
 */
final class RemoveConstraintToolTest
{
  private static final String DISEASE_CLASS = "http://purl.obolibrary.org/obo/DOID_4";
  private static final String LOINC_ONTOLOGY = "https://data.bioontology.org/ontologies/LOINC";

  @Test void removes_one_constraint_and_keeps_the_others()
  {
    String field = constrainedField();
    field = textOf(invokeTool(SetOntologyConstraintTool::handler, "set_ontology_constraint", Map.of(
        "field", field,
        "ontology_iri", LOINC_ONTOLOGY,
        "ontology_acronym", "LOINC",
        "ontology_name", "Logical Observation Identifiers")));

    McpSchema.CallToolResult result = invoke(Map.of("field", field, "iri", DISEASE_CLASS));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertFalse(yaml.contains(DISEASE_CLASS), "the class constraint must be gone; got: " + yaml);
    // The entry names the ontology by acronym; its BioPortal address is derived from that on read.
    assertTrue(yaml.contains("sourceAcronym: LOINC"), "the ontology constraint must survive; got: " + yaml);
    assertTrue(yaml.contains("controlled-term-field"),
        "a still-constrained field stays controlled-term; got: " + yaml);
  }

  @Test void removing_the_last_constraint_yields_a_text_shaped_field()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "field", constrainedField(), "iri", DISEASE_CLASS));

    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("type: text-field"),
        "an unconstrained controlled-term field is wire-identical to a text field; got: " + yaml);
    assertFalse(yaml.contains(DISEASE_CLASS), yaml);
  }

  @Test void refuses_to_orphan_a_controlled_term_default()
  {
    String field = textOf(invokeTool(SetIriDefaultValueTool::handler, "set_iri_default_value", Map.of(
        "field", constrainedField(),
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer")));

    McpSchema.CallToolResult result = invoke(Map.of("field", field, "iri", DISEASE_CLASS));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("default"),
        "error should direct at the default first; got: " + errorText(result));
  }

  @Test void unknown_iri_errors_with_a_kind_labelled_listing()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "field", constrainedField(), "iri", "https://example.org/not-attached"));

    assertTrue(result.isError());
    String message = errorText(result);
    assertTrue(message.contains("class: " + DISEASE_CLASS),
        "error should list current constraints with kinds; got: " + message);
    assertTrue(message.contains("(disease)"),
        "the listing should carry labels; got: " + message);
  }

  @Test void rejects_an_unconstrained_field()
  {
    String textField = textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("type", "text-field", "name", "Note")));

    McpSchema.CallToolResult result = invoke(Map.of("field", textField, "iri", DISEASE_CLASS));

    assertTrue(result.isError());
    assertTrue(errorText(result).contains("nothing to remove"), errorText(result));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return RemoveConstraintTool.handler(null,
        new McpSchema.CallToolRequest("remove_constraint", args));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(io.modelcontextprotocol.server.McpSyncServerExchange e,
        McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture step '" + name + "' must succeed; got: " + errorText(result));
    return result;
  }

  /** A controlled-term field carrying one class constraint (the DOID disease class). */
  private static String constrainedField()
  {
    String field = textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("type", "controlled-term-field", "name", "Disease")));
    return textOf(invokeTool(SetClassConstraintTool::handler, "set_class_constraint", Map.of(
        "field", field,
        "class_iri", DISEASE_CLASS,
        "ontology_acronym", "DOID",
        "pref_label", "disease")));
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    assertNotNull(result.content());
    assertFalse(result.content().isEmpty());
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code set_literal_annotation} / {@code set_iri_annotation} / {@code remove_annotation}
 * — root-level annotation editing across the annotatable kinds (template, element, field, template
 * instance), with element instances rejected.
 */
final class AnnotationToolsTest
{

  /** Stands in for a template a repository has stored: only such a template can be based on. */
  private static final String STORED_TEMPLATE_IRI =
      "https://repo.metadatacenter.org/templates/f0c1a2b3-4d5e-6f70-8192-a3b4c5d6e7f8";
  @Test void sets_a_literal_annotation_on_a_template()
  {
    McpSchema.CallToolResult result = setLiteral(createTemplate("Demographics"),
        "skos:prefLabel", "Patient Demographics");
    assertFalse(result.isError(), errorText(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("skos:prefLabel"), "annotation property must be present; got:\n" + yaml);
    assertTrue(yaml.contains("Patient Demographics"), "annotation value must be present; got:\n" + yaml);
  }

  @Test void sets_an_iri_annotation_on_a_field()
  {
    McpSchema.CallToolResult result = setIri(createField("Patient name", "text-field"),
        "skos:exactMatch", "https://example.org/term/42");
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("https://example.org/term/42"),
        "IRI annotation value must be present; got:\n" + textOf(result));
  }

  @Test void sets_an_annotation_on_an_element()
  {
    McpSchema.CallToolResult result = setLiteral(createElement("Address"), "rdfs:comment", "a postal address");
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("rdfs:comment"));
  }

  @Test void sets_an_annotation_on_a_template_instance()
  {
    String instance = textOf(invokeTool(CreateTemplateInstanceTool::handler, "create_template_instance",
        Map.of("template", createTemplate("Demographics"))));
    McpSchema.CallToolResult result = setLiteral(instance, "prov:wasAttributedTo", "Alice");
    assertFalse(result.isError(), errorText(result));
    assertTrue(textOf(result).contains("prov:wasAttributedTo"));
  }

  @Test void overwrites_an_existing_annotation()
  {
    String once = textOf(setLiteral(createTemplate("Demographics"), "skos:prefLabel", "First"));
    McpSchema.CallToolResult twice = setLiteral(once, "skos:prefLabel", "Second");
    assertFalse(twice.isError(), errorText(twice));
    String yaml = textOf(twice);
    assertTrue(yaml.contains("Second"), "overwrite must keep the new value; got:\n" + yaml);
    assertFalse(yaml.contains("First"), "overwrite must drop the old value; got:\n" + yaml);
  }

  @Test void removes_an_annotation()
  {
    String annotated = textOf(setLiteral(createTemplate("Demographics"), "skos:prefLabel", "Gone soon"));
    McpSchema.CallToolResult removed = remove(annotated, "skos:prefLabel");
    assertFalse(removed.isError(), errorText(removed));
    assertFalse(textOf(removed).contains("skos:prefLabel"),
        "removed annotation must be gone; got:\n" + textOf(removed));
  }

  @Test void remove_is_idempotent()
  {
    McpSchema.CallToolResult result = remove(createTemplate("Demographics"), "skos:prefLabel");
    assertFalse(result.isError(), "removing an absent annotation must succeed: " + errorText(result));
  }

  @Test void rejects_an_element_instance()
  {
    String entry = textOf(invokeTool(CreateElementInstanceTool::handler, "create_element_instance",
        Map.of("element", addressElement())));
    McpSchema.CallToolResult result = setLiteral(entry, "skos:prefLabel", "nope");
    assertTrue(result.isError(), "element instances do not carry annotations");
    assertTrue(errorText(result).contains("element instance"),
        "error should explain element instances are unsupported; got: " + errorText(result));
  }

  @Test void rejects_a_non_absolute_iri()
  {
    McpSchema.CallToolResult result = setIri(createField("Patient name", "text-field"),
        "skos:exactMatch", "not-a-uri");
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("absolute IRI"));
  }

  @Test void rejects_missing_arguments()
  {
    McpSchema.CallToolResult result = SetLiteralAnnotationTool.handler(null,
        new McpSchema.CallToolRequest("set_literal_annotation", Map.of("artifact", createTemplate("X"))));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("annotation"));
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  private static McpSchema.CallToolResult setLiteral(String artifact, String annotation, String value)
  {
    return SetLiteralAnnotationTool.handler(null, new McpSchema.CallToolRequest(
        "set_literal_annotation", Map.of("artifact", artifact, "annotation", annotation, "value", value)));
  }

  private static McpSchema.CallToolResult setIri(String artifact, String annotation, String iri)
  {
    return SetIriAnnotationTool.handler(null, new McpSchema.CallToolRequest(
        "set_iri_annotation", Map.of("artifact", artifact, "annotation", annotation, "iri", iri)));
  }

  private static McpSchema.CallToolResult remove(String artifact, String annotation)
  {
    return RemoveAnnotationTool.handler(null, new McpSchema.CallToolRequest(
        "remove_annotation", Map.of("artifact", artifact, "annotation", annotation)));
  }

  private static String createTemplate(String name)
  {
    return textOf(invokeTool(CreateTemplateTool::handler, "create_template", Map.of("name", name, "id", STORED_TEMPLATE_IRI)));
  }

  private static String createElement(String name)
  {
    return textOf(invokeTool(CreateElementTool::handler, "create_element", Map.of("name", name)));
  }

  private static String createField(String name, String type)
  {
    return textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", name, "type", type)));
  }

  private static String addressElement()
  {
    String street = textOf(invokeTool(CreateFieldTool::handler, "create_field",
        Map.of("name", "Street", "type", "text-field")));
    return textOf(invokeTool(AddFieldTool::handler, "add_field", Map.of(
        "parent", textOf(invokeTool(CreateElementTool::handler, "create_element", Map.of("name", "Address"))),
        "child", street,
        "key", "Street")));
  }

  private interface Handler
  {
    McpSchema.CallToolResult handle(McpSyncServerExchange e, McpSchema.CallToolRequest r);
  }

  private static McpSchema.CallToolResult invokeTool(Handler handler, String name, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.handle(null, new McpSchema.CallToolRequest(name, args));
    assertFalse(result.isError(), "fixture '" + name + "' must succeed; got: " + errorText(result));
    return result;
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String errorText(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

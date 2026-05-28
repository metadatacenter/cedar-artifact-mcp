package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SetControlledTermFieldValueToolTest
{
  private static final String FAKE_BASED_ON = "https://example.org/templates/test-fixture";

  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void sets_controlled_term_value() throws Exception
  {
    // Build a controlled-term field by authoring it with a class constraint — that's
    // what makes the library classify it as ControlledTermField in the schema.
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Diagnosis template\n"
            + "description: With diagnosis\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: diagnosis\n"
            + "    type: controlled-term-field\n"
            + "    name: Diagnosis\n"
            + "    description: ICD diagnosis\n"
            + "    datatype: iri\n"
            + "    values:\n"
            + "      - type: class\n"
            + "        label: disease\n"
            + "        acronym: DOID\n"
            + "        termType: class\n"
            + "        termLabel: disease\n"
            + "        iri: http://purl.obolibrary.org/obo/DOID_4\n");
    String instanceJson = createInstance(templateJson, "Patient 42");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "diagnosis",
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer",
        "pref_label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    JsonNode diag = rendered.path("diagnosis");
    assertEquals("http://purl.obolibrary.org/obo/DOID_1612", diag.path("@id").asText());
    assertEquals("breast cancer", diag.path("rdfs:label").asText());
    assertEquals("breast cancer", diag.path("skos:prefLabel").asText());
  }

  @Test void pref_label_defaults_to_label_when_omitted() throws Exception
  {
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: Diagnosis template\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: diagnosis\n"
            + "    type: controlled-term-field\n"
            + "    name: Diagnosis\n"
            + "    description: ICD diagnosis\n"
            + "    datatype: iri\n"
            + "    values:\n"
            + "      - type: class\n"
            + "        label: disease\n"
            + "        acronym: DOID\n"
            + "        termType: class\n"
            + "        termLabel: disease\n"
            + "        iri: http://purl.obolibrary.org/obo/DOID_4\n");
    String instanceJson = createInstance(templateJson, "P");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "diagnosis",
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    assertEquals("breast cancer",
        rendered.path("diagnosis").path("skos:prefLabel").asText());
  }

  @Test void rejects_path_to_non_controlled_term_field()
  {
    // A plain text-field schema (with no controlled-term constraint) won't be
    // classified as ControlledTermField — the wire collision documented in memory.
    // The setter must refuse it cleanly and point at add_*_constraint.
    String templateJson = compileTemplate(
        "type: template\n"
            + "name: T\n"
            + "description: T\n"
            + "version: 0.0.1\n"
            + "status: draft\n"
            + "modelVersion: 1.6.0\n"
            + "children:\n"
            + "  - key: note\n"
            + "    type: text-field\n"
            + "    name: Note\n");
    String instanceJson = createInstance(templateJson, "I");

    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", templateJson,
        "instance_json", instanceJson,
        "field_path", "note",
        "iri", "https://example.org/x",
        "label", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("controlled-term")
            && errorText(result).contains("set_class_constraint"),
        "error should mention controlled-term and add_*_constraint guidance; got: "
            + errorText(result));
  }

  @Test void rejects_missing_label()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "template_json", "{}",
        "instance_json", "{}",
        "field_path", "x",
        "iri", "https://x.example"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("label"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetControlledTermFieldValueTool.handler(null,
        new McpSchema.CallToolRequest("set_controlled_term_field_value", args));
  }

  private static String compileTemplate(String yaml)
  {
    McpSchema.CallToolResult result = TemplateFromYamlTool.handler(null,
        new McpSchema.CallToolRequest("template_from_yaml", Map.of("yaml", yaml)));
    assertFalse(result.isError(),
        "fixture template must compile cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private static String createInstance(String templateJson, String name)
  {
    McpSchema.CallToolResult result = CreateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_instance", Map.of(
            "template_json", templateJson,
            "is_based_on", FAKE_BASED_ON,
            "name", name)));
    assertFalse(result.isError(),
        "fixture instance must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  private ObjectNode parseJson(McpSchema.CallToolResult result) throws Exception
  {
    String text = textOf(result);
    JsonNode node = jackson.readTree(text);
    assertTrue(node.isObject(), "result must be a JSON object; got: " + text);
    return (ObjectNode) node;
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ValidationReport;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SetControlledTermDefaultValueToolTest
{
  private ModelValidator cedarValidator;

  @BeforeEach void setUp()
  {
    cedarValidator = new CedarValidator();
  }

  @Test void sets_controlled_term_default() throws Exception
  {
    // Build the controlled-term field by creating an empty one and attaching a class
    // constraint (both tools return YAML — the exchange form).
    String fieldYaml = constrainedControlledTermField();

    McpSchema.CallToolResult result = invoke(Map.of(
        "field", fieldYaml,
        "iri", "http://purl.obolibrary.org/obo/DOID_1612",
        "label", "breast cancer"));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = renderJson(result);
    JsonNode def = rendered.path("_valueConstraints").path("defaultValue");
    assertEquals("http://purl.obolibrary.org/obo/DOID_1612",
        def.path("termUri").asText(),
        "default IRI must appear; got: " + def);
    assertEquals("breast cancer", def.path("rdfs:label").asText(),
        "default label must appear; got: " + def);

    ValidationReport report = cedarValidator.validateTemplateField(rendered);
    assertEquals("true", report.getValidationStatus());
  }

  @Test void rejects_text_field_without_constraint()
  {
    // A plain text-field reads back as TextField (the wire collision); the tool must
    // refuse and direct the user to add_*_constraint first.
    String fieldYaml = createField("Note", "text-field");

    McpSchema.CallToolResult result = invoke(Map.of(
        "field", fieldYaml,
        "iri", "https://example.org/x",
        "label", "x"));
    assertTrue(result.isError());
    assertTrue(errorText(result).toLowerCase().contains("text-field")
            || errorText(result).toLowerCase().contains("controlled-term"),
        "error should redirect to constraint tools; got: " + errorText(result));
  }

  @Test void rejects_missing_label()
  {
    McpSchema.CallToolResult result = invoke(Map.of(
        "field", "{}",
        "iri", "https://x.example"));
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("label"));
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return SetControlledTermDefaultValueTool.handler(null,
        new McpSchema.CallToolRequest("set_controlled_term_default_value", args));
  }

  private static String createField(String name, String type)
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("name", name, "type", type)));
    assertFalse(result.isError(),
        "fixture field must build cleanly; got: " + errorText(result));
    return textOf(result);
  }

  /** A controlled-term field with one class constraint attached — built entirely from YAML-returning tools. */
  private static String constrainedControlledTermField()
  {
    String empty = createField("Diagnosis", "controlled-term-field");
    McpSchema.CallToolResult result = SetClassConstraintTool.handler(null,
        new McpSchema.CallToolRequest("set_class_constraint", Map.of(
            "field", empty,
            "class_iri", "http://purl.obolibrary.org/obo/DOID_4",
            "ontology_acronym", "DOID",
            "label", "disease",
            "pref_label", "disease")));
    assertFalse(result.isError(),
        "fixture constraint must apply cleanly; got: " + errorText(result));
    return textOf(result);
  }

  /** Read the tool's YAML output back to the model, then render JSON for assertions. */
  private static ObjectNode renderJson(McpSchema.CallToolResult result)
  {
    String yaml = textOf(result);
    Object parsed = new Yaml().load(yaml);
    assertTrue(parsed instanceof Map, "result must be a YAML mapping; got: " + yaml);
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet())
      map.put(String.valueOf(e.getKey()), e.getValue());
    FieldSchemaArtifact field = new YamlArtifactReader(true).readFieldSchemaArtifact(map);
    return new JsonArtifactRenderer().renderFieldSchemaArtifact(field);
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

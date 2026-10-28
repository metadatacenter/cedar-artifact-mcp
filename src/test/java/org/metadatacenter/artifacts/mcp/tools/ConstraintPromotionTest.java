package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TextField;
import org.metadatacenter.artifacts.model.core.Version;
import org.metadatacenter.artifacts.model.core.fields.constraints.ValueConstraints;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A text field promoted to a controlled-term field by its first constraint keeps everything it
 * already said.
 *
 * <p>Each constraint suite builds its fixture from a bare name and type, so a promotion that dropped
 * every setting a stored field carries would pass all of them, and the reduced field would still
 * validate. This fixture sets every one of those settings to a non-default value. Requirement,
 * recommendation, multiplicity and the property IRI are not among them: a parent template states
 * those for its child, and a standalone field carries none of them.
 */
final class ConstraintPromotionTest
{
  private static final String NAME = "Disease";
  private static final String DESCRIPTION = "The disease studied";

  static Stream<Arguments> tools()
  {
    return Stream.of(
        Arguments.of("set_class_constraint", (Function<Map<String, Object>, McpSchema.CallToolResult>)
            arguments -> SetClassConstraintTool.handler(null,
                ToolCalls.request("set_class_constraint", arguments)),
            Map.of("class_iri", "http://purl.obolibrary.org/obo/DOID_4", "ontology_acronym", "DOID",
                "label", "disease", "pref_label", "disease")),
        Arguments.of("set_ontology_constraint", (Function<Map<String, Object>, McpSchema.CallToolResult>)
            arguments -> SetOntologyConstraintTool.handler(null,
                ToolCalls.request("set_ontology_constraint", arguments)),
            Map.of("ontology_iri", "https://data.bioontology.org/ontologies/DOID", "ontology_acronym", "DOID",
                "ontology_name", "Human Disease Ontology")),
        Arguments.of("set_branch_constraint", (Function<Map<String, Object>, McpSchema.CallToolResult>)
            arguments -> SetBranchConstraintTool.handler(null,
                ToolCalls.request("set_branch_constraint", arguments)),
            Map.of("branch_iri", "http://purl.obolibrary.org/obo/DOID_4", "ontology_name", "Human Disease Ontology",
                "ontology_acronym", "DOID", "branch_label", "Disease")),
        Arguments.of("set_valueset_constraint", (Function<Map<String, Object>, McpSchema.CallToolResult>)
            arguments -> SetValueSetConstraintTool.handler(null,
                ToolCalls.request("set_valueset_constraint", arguments)),
            Map.of("value_set_iri", "https://example.org/cedarvs/area-units", "vs_collection", "CEDARVS",
                "name", "Area units")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tools")
  void the_first_constraint_keeps_every_setting_of_the_text_field(
      String tool, Function<Map<String, Object>, McpSchema.CallToolResult> handler, Map<String, Object> constraint)
  {
    TextField stored = TextField.builder()
        .withName(NAME)
        .withDescription(DESCRIPTION)
        .withIdentifier("disease-v2")
        .withVersion(Version.fromString("2.0.0"))
        .withPreferredLabel("Disease name")
        .withAlternateLabels(List.of("Condition", "Illness"))
        .withHidden(true)
        .withContinuePreviousLine(true)
        .withValueRecommendationEnabled(true)
        .build();
    String fieldJson = new JsonArtifactRenderer().renderFieldSchemaArtifact(stored).toString();

    Map<String, Object> arguments = new HashMap<>(constraint);
    arguments.put("field", fieldJson);
    McpSchema.CallToolResult result = handler.apply(arguments);
    assertFalse(result.isError(), tool + ": " + textOf(result));

    FieldSchemaArtifact promoted = readBack(result);
    ValueConstraints constraints = promoted.valueConstraints().orElseThrow();
    assertAll(tool,
        () -> assertEquals(NAME, promoted.name()),
        () -> assertEquals(DESCRIPTION, promoted.description()),
        () -> assertEquals("disease-v2", promoted.identifier().orElse(null)),
        () -> assertEquals("2.0.0", promoted.version().map(Version::toString).orElse(null)),
        () -> assertEquals("Disease name", promoted.preferredLabel().orElse(null)),
        () -> assertEquals(List.of("Condition", "Illness"), promoted.alternateLabels()),
        () -> assertTrue(promoted.fieldUi().hidden(), "hidden"),
        () -> assertTrue(promoted.fieldUi().continuePreviousLine(), "continuePreviousLine"),
        () -> assertTrue(promoted.fieldUi().valueRecommendationEnabled(), "valueRecommendationEnabled"),
        () -> assertTrue(constraints.isControlledTermValueConstraint(), "the constraint was applied"));
  }

  @SuppressWarnings("unchecked")
  private static FieldSchemaArtifact readBack(McpSchema.CallToolResult result)
  {
    Object loaded = new org.yaml.snakeyaml.Yaml().load(textOf(result));
    assertTrue(loaded instanceof Map, "result must be a YAML mapping");
    return ArtifactExchange.readFieldSchemaYaml(new LinkedHashMap<>((Map<String, Object>) loaded));
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    if (result.content() == null || result.content().isEmpty()) return "(no content)";
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

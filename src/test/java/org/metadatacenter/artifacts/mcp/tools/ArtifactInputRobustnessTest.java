package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting robustness of the artifact parameters, exercised through representative tools:
 *
 * <ul>
 *   <li><strong>Format auto-detection</strong> — every artifact parameter accepts YAML or JSON
 *       interchangeably (a JSON Schema export and the YAML exchange form of the same artifact
 *       must behave identically);</li>
 *   <li><strong>Garbage tolerance</strong> — malformed, mis-shaped, wrong-kind, or blank input
 *       must come back as a clean {@code isError=true} result with a message, never as a thrown
 *       exception.</li>
 * </ul>
 */
final class ArtifactInputRobustnessTest
{

  /** Stands in for a template a repository has stored: only such a template can be based on. */
  private static final String STORED_TEMPLATE_IRI =
      "https://repo.metadatacenter.org/templates/f0c1a2b3-4d5e-6f70-8192-a3b4c5d6e7f8";
  // ---------------------------------------------------------------- fixtures

  private static String templateYaml()
  {
    return textOf(CreateTemplateTool.handler(null,
        new McpSchema.CallToolRequest("create_template", Map.of("name", "Robustness", "id", STORED_TEMPLATE_IRI))));
  }

  private static String templateJson(String templateYaml)
  {
    return textOf(RenderSchemaArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_schema_artifact", Map.of("schema_artifact", templateYaml, "format", "json"))));
  }

  private static String fieldYaml()
  {
    return textOf(CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field",
            Map.of("type", "text-field", "name", "Probe"))));
  }

  private static String fieldJson(String fieldYaml)
  {
    return textOf(RenderSchemaArtifactTool.handler(null,
        new McpSchema.CallToolRequest("render_schema_artifact", Map.of("schema_artifact", fieldYaml, "format", "json"))));
  }

  // ---------------------------------------------------------------- auto-detection

  @Test void add_field_accepts_yaml_parent_with_json_child()
  {
    String template = templateYaml();
    String field = fieldJson(fieldYaml());

    McpSchema.CallToolResult result = AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", Map.of("parent", template, "child", field)));

    assertFalse(result.isError(), textOf(result));
    assertTrue(textOf(result).contains("key: \"Probe\""),
        "JSON child must graft like its YAML twin; got:\n" + textOf(result));
  }

  @Test void add_field_accepts_json_parent_with_yaml_child()
  {
    String template = templateJson(templateYaml());
    String field = fieldYaml();

    McpSchema.CallToolResult result = AddFieldTool.handler(null,
        new McpSchema.CallToolRequest("add_field", Map.of("parent", template, "child", field)));

    assertFalse(result.isError(), textOf(result));
    assertTrue(textOf(result).contains("key: \"Probe\""),
        "YAML child must graft onto a JSON parent; got:\n" + textOf(result));
  }

  @Test void both_formats_of_the_same_template_create_equivalent_instances()
  {
    String yamlForm = templateYaml();
    String jsonForm = templateJson(yamlForm);

    String fromYaml = textOf(CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", Map.of("template", yamlForm))));
    String fromJson = textOf(CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", Map.of("template", jsonForm))));

    // Identity (@id) is freshly minted per call; the derived isBasedOn line must agree.
    String basedOnFromYaml = lineStartingWith(fromYaml, "isBasedOn:");
    String basedOnFromJson = lineStartingWith(fromJson, "isBasedOn:");
    assertTrue(basedOnFromYaml != null && basedOnFromYaml.equals(basedOnFromJson),
        "both serializations of one template must derive the same isBasedOn; got "
            + basedOnFromYaml + " vs " + basedOnFromJson);
  }

  @Test void validate_instance_accepts_mixed_formats()
  {
    String templateYaml = templateYaml();
    String templateJson = templateJson(templateYaml);
    String instanceYaml = textOf(CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", Map.of("template", templateYaml))));

    McpSchema.CallToolResult result = ValidateInstanceArtifactTool.handler(null,
        new McpSchema.CallToolRequest("validate_instance_artifact",
            Map.of("schema_artifact", templateJson, "instance_artifact", instanceYaml)));

    assertFalse(result.isError(), textOf(result));
    assertTrue(textOf(result).contains("\"valid\" : true") || textOf(result).contains("\"valid\": true"),
        "JSON template + YAML instance must validate; got: " + textOf(result));
  }

  // ---------------------------------------------------------------- garbage tolerance

  /** Inputs that must never crash a tool: prose, broken JSON, non-mapping YAML, a YAML mapping
   *  that is not CEDAR, and whitespace. */
  private static final String[] GARBAGE = {
      "this is not an artifact, just prose",
      "{ \"broken\": ",
      "- a\n- b\n- c",
      "type: nonsense\nfoo: [unclosed",
      "   \n  \n",
  };

  private static void assertCleanErrors(
      String toolName,
      BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
          McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
      String argName)
  {
    for (String garbage : GARBAGE) {
      McpSchema.CallToolResult result;
      try {
        result = handler.apply(null, new McpSchema.CallToolRequest(toolName, Map.of(argName, garbage)));
      } catch (RuntimeException e) {
        throw new AssertionError(toolName + " threw instead of returning an error result for "
            + "input <" + garbage.replace("\n", "\\n") + ">: " + e, e);
      }
      assertTrue(result.isError(),
          toolName + " must flag garbage input <" + garbage.replace("\n", "\\n") + "> as an error");
      assertFalse(textOf(result).isBlank(), toolName + " error must carry a message");
    }
  }

  @Test void add_field_survives_garbage_parents()
  {
    assertCleanErrors("add_field",
        (exchange, request) -> AddFieldTool.handler(null,
            new McpSchema.CallToolRequest("add_field",
                Map.of("parent", request.arguments().get("parent").toString(), "child", fieldYaml()))),
        "parent");
  }

  @Test void create_template_instance_survives_garbage_templates()
  {
    assertCleanErrors("create_template_instance", CreateTemplateInstanceTool::handler, "template");
  }

  @Test void remove_constraint_survives_garbage_fields()
  {
    assertCleanErrors("remove_constraint", RemoveConstraintTool::handler, "field");
  }

  @Test void reorder_children_survives_garbage_parents()
  {
    assertCleanErrors("reorder_children", ReorderChildrenTool::handler, "parent");
  }

  @Test void create_element_instance_survives_garbage_elements()
  {
    assertCleanErrors("create_element_instance", CreateElementInstanceTool::handler, "element");
  }

  @Test void set_element_instance_survives_garbage_artifacts()
  {
    assertCleanErrors("set_element_instance", SetElementInstanceTool::handler, "template");
  }

  @Test void validate_instance_artifact_survives_garbage_schemas()
  {
    assertCleanErrors("validate_instance_artifact", ValidateInstanceArtifactTool::handler, "schema_artifact");
  }

  @Test void set_literal_default_value_survives_garbage_fields()
  {
    assertCleanErrors("set_literal_default_value",
        (exchange, request) -> SetLiteralDefaultValueTool.handler(null,
            new McpSchema.CallToolRequest("set_literal_default_value",
                Map.of("field", request.arguments().get("field").toString(), "value", "x"))),
        "field");
  }

  @Test void template_to_yaml_survives_garbage()
  {
    assertCleanErrors("render_schema_artifact", RenderSchemaArtifactTool::handler, "schema_artifact");
  }

  @Test void wrong_artifact_kind_is_a_clean_error()
  {
    // An instance handed to a template parameter must be rejected, not half-read.
    String templateYaml = templateYaml();
    String instanceYaml = textOf(CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", Map.of("template", templateYaml))));

    McpSchema.CallToolResult result = CreateTemplateInstanceTool.handler(null,
        new McpSchema.CallToolRequest("create_template_instance", Map.of("template", instanceYaml)));

    assertTrue(result.isError(), "an instance is not a template; got: " + textOf(result));
  }

  // ---------------------------------------------------------------- helpers

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String lineStartingWith(String text, String prefix)
  {
    for (String line : text.split("\n"))
      if (line.startsWith(prefix))
        return line.trim();
    return null;
  }
}

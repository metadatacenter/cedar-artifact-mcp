package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every kind of artifact, in each form a tool takes it in, through the tools that take any kind.
 *
 * <p>Which kind an artifact is was decided in seven places, and they disagreed: one matched a
 * substring of {@code @type}, so a template instance typed with an IRI containing "Field" was read
 * as a field by the file tools, and another took every JSON without {@code schema:isBasedOn} for an
 * element instance. {@code validate_schema_artifact} gave a JSON template and the same template in
 * YAML different verdicts, because only the YAML was read by the library. And the tools that edit
 * an instance never validated it, though the README promised every result had been.
 *
 * <p>Each kind is made by the tools themselves and rendered as JSON, expanded YAML and compact YAML.
 * The matrix requires that the form never changes the answer: the same kind detected, the same
 * verdict, the same artifact read back, and an edited instance that its validator accepts.
 */
final class ToolFormatMatrixTest
{
  private static final String STORED_TEMPLATE_IRI =
      "https://repo.metadatacenter.org/templates/7a1c2b3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d";
  private static final String STORED_ELEMENT_IRI =
      "https://repo.metadatacenter.org/template-elements/8b2d3c4e-5f6a-7b8c-9d0e-1f2a3b4c5d6e";

  private enum Form { JSON, YAML, COMPACT_YAML }

  private record Kind(String name, boolean schema, String yaml) {}

  private static String call(BiFunction<Object, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler,
                             String tool, Map<String, Object> args)
  {
    McpSchema.CallToolResult result = handler.apply(null, new McpSchema.CallToolRequest(tool, args));
    String text = textOf(result);
    if (Boolean.TRUE.equals(result.isError()))
      throw new AssertionError("fixture step '" + tool + "' failed: " + text);
    return text;
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static McpSchema.CallToolResult tool(
      BiFunction<Object, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler, String tool,
      Map<String, Object> args)
  {
    return handler.apply(null, new McpSchema.CallToolRequest(tool, args));
  }

  private static String template()
  {
    String template = call((e, r) -> CreateTemplateTool.handler(null, r), "create_template",
        Map.of("name", "Matrix", "id", STORED_TEMPLATE_IRI));
    String field = call((e, r) -> CreateFieldTool.handler(null, r), "create_field",
        Map.of("type", "text-field", "name", "Probe"));
    return call((e, r) -> AddFieldTool.handler(null, r), "add_field", Map.of("parent", template, "child", field));
  }

  private static String element()
  {
    String element = call((e, r) -> CreateElementTool.handler(null, r), "create_element",
        Map.of("name", "Group", "id", STORED_ELEMENT_IRI));
    String field = call((e, r) -> CreateFieldTool.handler(null, r), "create_field",
        Map.of("type", "text-field", "name", "Inner"));
    return call((e, r) -> AddFieldTool.handler(null, r), "add_field", Map.of("parent", element, "child", field));
  }

  private static String instance(String template)
  {
    String instance = call((e, r) -> CreateTemplateInstanceTool.handler(null, r), "create_template_instance",
        Map.of("template", template));
    return call((e, r) -> SetLiteralFieldValueTool.handler(null, r), "set_literal_field_value",
        Map.of("template", template, "instance", instance, "field_path", "Probe", "value", "measured"));
  }

  /** Every kind, as the exchange YAML the tools hand each other. */
  private static List<Kind> kinds()
  {
    String template = template();
    String element = element();
    List<Kind> kinds = new ArrayList<>();
    kinds.add(new Kind("template", true, template));
    kinds.add(new Kind("element", true, element));
    kinds.add(new Kind("field", true, call((e, r) -> CreateFieldTool.handler(null, r), "create_field",
        Map.of("type", "text-field", "name", "Probe"))));
    kinds.add(new Kind("static field", true, call((e, r) -> CreateFieldTool.handler(null, r), "create_field",
        Map.of("type", "static-section-break", "name", "Section"))));
    kinds.add(new Kind("template instance", false, instance(template)));
    kinds.add(new Kind("element instance", false, call((e, r) -> CreateElementInstanceTool.handler(null, r),
        "create_element_instance", Map.of("element", element))));
    return kinds;
  }

  private static String render(Kind kind, Form form)
  {
    if (form == Form.YAML)
      return kind.yaml();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put(kind.schema() ? "schema_artifact" : "instance_artifact", kind.yaml());
    args.put("format", form == Form.JSON ? "json" : "yaml");
    if (form == Form.COMPACT_YAML)
      args.put("compact", true);
    return kind.schema()
        ? call((e, r) -> RenderSchemaArtifactTool.handler(null, r), "render_schema_artifact", args)
        : call((e, r) -> RenderInstanceArtifactTool.handler(null, r), "render_instance_artifact", args);
  }

  /** What validate_schema_artifact says, reduced to what may not depend on the form. */
  private static String schemaVerdict(String artifact)
  {
    McpSchema.CallToolResult result = tool((e, r) -> ValidateSchemaArtifactTool.handler(null, r),
        "validate_schema_artifact", Map.of("artifact", artifact));
    String text = textOf(result);
    if (Boolean.TRUE.equals(result.isError()))
      return text.contains("validate_instance_artifact") ? "an instance" : "error: " + text;
    return text.contains("\"valid\":true") || text.contains("\"valid\" : true") ? "valid" : "invalid";
  }

  @Test void validate_schema_artifact_gives_every_form_the_same_verdict()
  {
    List<String> differences = new ArrayList<>();
    for (Kind kind : kinds()) {
      String expected = kind.schema() ? "valid" : "an instance";
      for (Form form : Form.values()) {
        String verdict = schemaVerdict(render(kind, form));
        if (!verdict.equals(expected))
          differences.add(kind.name() + " as " + form + ": " + verdict);
      }
    }
    assertEquals(List.of(), differences);
  }

  @Test void an_artifact_the_library_cannot_read_is_invalid_in_either_form()
  {
    // A static field whose version is not one. The validator this server is built on states no
    // version for a static field, and the reader refuses one, so only asking the reader catches it
    // in JSON.
    Kind section = new Kind("static field", true, call((e, r) -> CreateFieldTool.handler(null, r), "create_field",
        Map.of("type", "static-section-break", "name", "Section")));
    String refusedJson = render(section, Form.JSON).replaceFirst("(\"pav:version\"\\s*:\\s*)\"[^\"]*\"", "$1\"banana\"");
    String refusedYaml = section.yaml().replaceFirst("(?m)^version: .*$", "version: \"banana\"");
    assertEquals(List.of("invalid", "invalid"), List.of(schemaVerdict(refusedJson), schemaVerdict(refusedYaml)),
        refusedJson + "\n" + refusedYaml);
  }

  @Test void the_file_tools_read_every_kind_the_same_from_either_form(@TempDir Path dir) throws Exception
  {
    List<Kind> kinds = new ArrayList<>(kinds());
    // An instance whose own type IRI contains "Field", which a substring match read as a field.
    Kind instance = kinds.stream().filter(k -> k.name().equals("template instance")).findFirst().orElseThrow();
    String typed = render(instance, Form.JSON).replaceFirst("\\{", "{\"@type\": \"https://example.org/FieldStudy\",");
    List<String> differences = new ArrayList<>();
    for (Kind kind : kinds) {
      String fromYaml = read(dir, kind.name() + ".yaml", kind.yaml());
      String fromJson = read(dir, kind.name() + ".json", render(kind, Form.JSON));
      if (!fromYaml.equals(fromJson))
        differences.add(kind.name() + ": JSON reads back as\n" + fromJson + "\nand YAML as\n" + fromYaml);
    }
    String typedRead = read(dir, "typed-instance.json", typed);
    if (!typedRead.startsWith("type: instance") && !typedRead.contains("\ntype: instance"))
      differences.add("an instance typed FieldStudy reads back as\n" + typedRead);
    assertEquals(List.of(), differences);
  }

  private static String read(Path dir, String name, String content) throws Exception
  {
    Path file = dir.resolve(name.replace(' ', '-'));
    Files.writeString(file, content);
    McpSchema.CallToolResult result = tool((e, r) -> ReadArtifactFileTool.handler(null, r), "read_artifact_file",
        Map.of("path", file.toString(), "format", "yaml"));
    return (Boolean.TRUE.equals(result.isError()) ? "error: " : "") + textOf(result);
  }

  @Test void every_edited_instance_passes_validate_instance_artifact()
  {
    String template = call((e, r) -> AddElementTool.handler(null, r), "add_element",
        Map.of("parent", template(), "child", element()));
    String entry = call((e, r) -> CreateElementInstanceTool.handler(null, r), "create_element_instance",
        Map.of("element", element()));
    String grafted = call((e, r) -> SetElementInstanceTool.handler(null, r), "set_element_instance",
        Map.of("template", template, "instance", instance(template), "field_path", "Group", "element_instance", entry));
    String filled = call((e, r) -> SetLiteralFieldValueTool.handler(null, r), "set_literal_field_value",
        Map.of("template", template, "instance", grafted, "field_path", "Group/Inner", "value", "inside"));
    String report = call((e, r) -> ValidateInstanceArtifactTool.handler(null, r), "validate_instance_artifact",
        Map.of("schema_artifact", template, "instance_artifact", filled));
    assertEquals("{\"valid\":true}", report.replaceAll("\\s", ""), report);
  }
}

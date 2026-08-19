package org.metadatacenter.artifacts.mcp.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code set_options} — replaces a choice field's literal option list (display order,
 * optional pre-selected default), and for {@code create_field}'s inline {@code options}.
 */
final class SetOptionsToolTest
{
  private static String createField(String type, String name)
  {
    return textOf(CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of("type", type, "name", name))));
  }

  private static McpSchema.CallToolResult setOptions(Map<String, Object> args)
  {
    return SetOptionsTool.handler(null, new McpSchema.CallToolRequest("set_options", args));
  }

  @Test void sets_options_on_a_radio_field_in_order()
  {
    String field = createField("radio-field", "Sex");

    McpSchema.CallToolResult result = setOptions(Map.of(
        "field", field, "options", List.of("Male", "Female", "Prefer not to say")));

    assertFalse(result.isError(), textOf(result));
    String yaml = textOf(result);
    int male = yaml.indexOf("label: \"Male\"");
    int female = yaml.indexOf("label: \"Female\"");
    int prefer = yaml.indexOf("label: \"Prefer not to say\"");
    assertTrue(male >= 0 && female > male && prefer > female,
        "options must render in display order; got:\n" + yaml);
    assertFalse(yaml.contains("selected: true"), "no default was requested; got:\n" + yaml);
  }

  @Test void default_option_marks_exactly_one_option_selected()
  {
    String field = createField("single-select-list-field", "Continent");

    McpSchema.CallToolResult result = setOptions(Map.of(
        "field", field,
        "options", List.of("Europe", "Asia", "Other"),
        "default_option", "Other"));

    assertFalse(result.isError(), textOf(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("label: \"Other\""), yaml);
    assertTrue(yaml.indexOf("selected: true") > yaml.indexOf("label: \"Other\""),
        "the selected marker must sit on the named option; got:\n" + yaml);
  }

  @Test void replaces_rather_than_appends()
  {
    String field = createField("checkbox-field", "Symptoms");
    String first = textOf(setOptions(Map.of("field", field, "options", List.of("Fever", "Cough"))));

    McpSchema.CallToolResult second = setOptions(Map.of(
        "field", first, "options", List.of("Headache")));

    assertFalse(second.isError(), textOf(second));
    String yaml = textOf(second);
    assertTrue(yaml.contains("label: \"Headache\""), yaml);
    assertFalse(yaml.contains("label: \"Fever\""), "old options must be replaced; got:\n" + yaml);
    assertFalse(yaml.contains("label: \"Cough\""), "old options must be replaced; got:\n" + yaml);
  }

  @Test void rejects_a_non_choice_field_with_a_redirect()
  {
    McpSchema.CallToolResult result = setOptions(Map.of(
        "field", createField("text-field", "Name"), "options", List.of("A", "B")));

    assertTrue(result.isError());
    assertTrue(textOf(result).contains("set_*_constraint"),
        "error should redirect ontology cases; got: " + textOf(result));
  }

  @Test void rejects_a_default_option_not_in_the_list()
  {
    McpSchema.CallToolResult result = setOptions(Map.of(
        "field", createField("radio-field", "Sex"),
        "options", List.of("Male", "Female"),
        "default_option", "Unknown"));

    assertTrue(result.isError());
    assertTrue(textOf(result).contains("Unknown"), textOf(result));
  }

  @Test void rejects_empty_or_blank_options()
  {
    String field = createField("radio-field", "Sex");
    assertTrue(setOptions(Map.of("field", field, "options", List.of())).isError());
    assertTrue(setOptions(Map.of("field", field, "options", List.of("ok", "  "))).isError());
  }

  @Test void create_field_accepts_inline_options_for_choice_fields()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "multi-select-list-field", "name", "Tags",
            "options", List.of("alpha", "beta"))));

    assertFalse(result.isError(), textOf(result));
    String yaml = textOf(result);
    assertTrue(yaml.contains("label: \"alpha\"") && yaml.contains("label: \"beta\""),
        "inline options must reach the field; got:\n" + yaml);
  }

  @Test void create_field_rejects_options_on_a_non_choice_type()
  {
    McpSchema.CallToolResult result = CreateFieldTool.handler(null,
        new McpSchema.CallToolRequest("create_field", Map.of(
            "type", "numeric-field", "name", "Age", "options", List.of("1", "2"))));

    assertTrue(result.isError());
    assertTrue(textOf(result).contains("choice fields"), textOf(result));
  }

  private static String textOf(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}

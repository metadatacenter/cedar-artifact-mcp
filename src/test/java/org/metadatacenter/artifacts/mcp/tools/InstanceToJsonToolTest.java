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

final class InstanceToJsonToolTest
{
  private ObjectMapper jackson;

  @BeforeEach void setUp() { jackson = new ObjectMapper(); }

  @Test void compiles_minimal_instance_yaml() throws Exception
  {
    String yaml =
        "type: instance\n"
            + "name: Patient 42\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/abc-123\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);

    assertEquals("Patient 42", rendered.path("schema:name").asText());
    assertEquals("https://repo.metadatacenter.org/templates/abc-123",
        rendered.path("schema:isBasedOn").asText());
  }

  @Test void template_json_inflates_sparse_instance_to_complete_json() throws Exception
  {
    // A YAML instance is sparse — unset fields are omitted. Without the template, export carries
    // only the fields the instance holds. With the template, the instance is inflated so the
    // exported JSON carries every template field (the form CedarValidator / cedar-server expect).
    String templateJson = textOf(TemplateToJsonTool.handler(null,
        new McpSchema.CallToolRequest("template_to_json", Map.of("yaml",
            "type: template\nname: PatientStudy\nmodelVersion: 1.6.0\nversion: 0.0.1\nstatus: draft\n"
                + "children:\n"
                + "  - key: Patient Name\n    type: text-field\n    name: Patient Name\n"
                + "  - key: Age\n    type: numeric-field\n    name: Age\n    datatype: xsd:int\n"))));

    String sparseInstance =
        "type: instance\n"
            + "name: P1\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/x\n"
            + "children:\n"
            + "  Patient Name:\n    value: Alice\n";

    // Without the template: only the set field is present.
    ObjectNode bare = parseJson(invoke(Map.of("yaml", sparseInstance)));
    assertTrue(bare.has("Patient Name"), "the set field must be present; got: " + bare);
    assertFalse(bare.has("Age"), "without the template, the omitted field stays omitted; got: " + bare);

    // With the template: the omitted field is reconstructed (empty) so the JSON is complete.
    ObjectNode full = parseJson(invoke(Map.of("yaml", sparseInstance, "template_json", templateJson)));
    assertEquals("Alice", full.path("Patient Name").path("@value").asText());
    assertTrue(full.has("Age"), "with the template, the omitted field is reconstructed; got: " + full);
    assertTrue(full.path("Age").has("@type"),
        "reconstructed numeric field carries its @type seed; got: " + full.path("Age"));
  }

  @Test void mints_instance_id_when_omitted() throws Exception
  {
    // The instance's own @id is auto-minted when absent (DESIGN.md Principle 10); it is
    // distinct from isBasedOn, which still points at the template.
    String yaml =
        "type: instance\n"
            + "name: Patient 42\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/abc-123\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    ObjectNode rendered = parseJson(result);
    MintedIds.assertMintedId(rendered.get("@id"), "template-instances");
    assertEquals("https://repo.metadatacenter.org/templates/abc-123",
        rendered.path("schema:isBasedOn").asText(),
        "minting the instance @id must not disturb isBasedOn");
  }

  @Test void preserves_supplied_instance_id() throws Exception
  {
    String id = "https://repo.metadatacenter.org/template-instances/abc-123";
    String yaml =
        "type: instance\n"
            + "name: Patient 42\n"
            + "isBasedOn: https://repo.metadatacenter.org/templates/abc-123\n"
            + "id: " + id + "\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertFalse(result.isError(), errorText(result));
    assertEquals(id, parseJson(result).get("@id").asText(),
        "a supplied instance id must be preserved, not overwritten by minting");
  }

  @Test void rejects_yaml_with_wrong_top_level_type()
  {
    String yaml =
        "type: template\n"
            + "name: NotAnInstance\n"
            + "modelVersion: 1.6.0\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "type: template must not compile via instance_to_json; got: " + result);
  }

  @Test void rejects_missing_isBasedOn()
  {
    String yaml =
        "type: instance\n"
            + "name: Missing-isBasedOn\n";

    McpSchema.CallToolResult result = invoke(Map.of("yaml", yaml));

    assertTrue(result.isError(),
        "instance without isBasedOn must produce isError=true; got: " + result);
  }

  @Test void rejects_missing_yaml_argument()
  {
    McpSchema.CallToolResult result = invoke(Map.of());
    assertTrue(result.isError());
    assertTrue(errorText(result).contains("yaml"));
  }

  @Test void rejects_blank_yaml()
  {
    McpSchema.CallToolResult result = invoke(Map.of("yaml", "   \n  \n"));
    assertTrue(result.isError(), "blank yaml input must produce isError=true");
  }

  // helpers
  private static McpSchema.CallToolResult invoke(Map<String, Object> args)
  {
    return InstanceToJsonTool.handler(null,
        new McpSchema.CallToolRequest("instance_to_json", args));
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

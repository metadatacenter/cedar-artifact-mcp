package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.ControlledTermFieldInstance;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.core.fields.FieldInputType;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code set_controlled_term_field_value} — sets a controlled-term field's
 * value: the IRI ({@code @id}), human-readable label ({@code rdfs:label}), and the
 * preferred label ({@code skos:prefLabel}). The canonical input tuple mirrors what
 * {@code bioportal-term-mcp}'s {@code get_class} returns.
 *
 * <p>Note: an empty controlled-term-field schema is JSON-indistinguishable from a
 * plain text-field schema (the library only classifies a TEXTFIELD as
 * {@link ControlledTermField} once it carries a controlled-term constraint). This
 * tool refuses TEXTFIELD-without-constraint schemas — author the constraint first via
 * {@code set_class_constraint} / etc. so the schema declares itself as controlled-term.
 */
public final class SetControlledTermFieldValueTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private SetControlledTermFieldValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template as YAML. Must declare a controlled-term field at the "
            + "given path — i.e. the schema field carries a class/ontology/branch/"
            + "value-set constraint."));
    properties.put("instance_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the kind 'create_instance' returns)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the controlled-term field. Same syntax as 'set_field_value'."));
    properties.put("iri", Map.of(
        "type", "string",
        "description", "Class IRI to set as the field's @id."));
    properties.put("label", Map.of(
        "type", "string",
        "description",
        "Human-readable label for the class (rdfs:label). Required — controlled-term "
            + "instances are not useful without a label alongside the IRI."));
    properties.put("pref_label", Map.of(
        "type", "string",
        "description",
        "Preferred label (skos:prefLabel). Optional; defaults to the label when omitted. "
            + "May differ from the label when the class has multiple synonyms."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("template_json", "instance_json", "field_path", "iri", "label"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_controlled_term_field_value")
        .title("Set a controlled-term field on an instance")
        .description(
            "Sets the value of a controlled-term field instance — the IRI (@id), the "
                + "rdfs:label, and the skos:prefLabel — at a slash-separated field_path. "
                + "Returns the updated instance as expanded YAML.")
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template_json");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template_json is required and must not be blank");

    String instanceJsonText = stringArg(args, "instance_json");
    if (instanceJsonText == null || instanceJsonText.isBlank())
      return error("instance_json is required and must not be blank");

    String fieldPath = stringArg(args, "field_path");
    if (fieldPath == null || fieldPath.isBlank())
      return error("field_path is required and must not be blank");

    String iriArg = stringArg(args, "iri");
    if (iriArg == null || iriArg.isBlank())
      return error("iri is required and must not be blank");

    String label = stringArg(args, "label");
    if (label == null || label.isBlank())
      return error("label is required and must not be blank");

    URI iri;
    try {
      iri = new URI(iriArg);
    } catch (URISyntaxException e) {
      return error("iri is not a valid URI: " + e.getMessage());
    }

    String prefLabel = stringArg(args, "pref_label");  // optional
    if (prefLabel == null || prefLabel.isBlank())
      prefLabel = label;  // sensible default — most LLM workflows have both equal

    ObjectNode templateObject;
    try {
      templateObject = ArtifactExchange.toObjectNode(templateJsonText);
    } catch (RuntimeException e) {
      return error("template parse failed: " + e.getMessage());
    }

    TemplateSchemaArtifact template;
    try {
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template_json rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("template_json parse failed: " + e.getMessage());
    }

    ObjectNode instanceObject;
    try {
      instanceObject = ArtifactExchange.toObjectNode(instanceJsonText);
    } catch (RuntimeException e) {
      return error("instance parse failed: " + e.getMessage());
    }

    TemplateInstanceArtifact instance;
    try {
      instance = READER.readTemplateInstanceArtifact(instanceObject);
    } catch (ArtifactParseException e) {
      return error("instance_json rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("instance_json parse failed: " + e.getMessage());
    }

    FieldSchemaArtifact schemaField;
    try {
      schemaField = SchemaPaths.resolveField(template, fieldPath);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    // A TEXTFIELD-shape schema without any controlled-term constraint round-trips to
    // TextField rather than ControlledTermField (the library's wire-collision; see
    // memory). Refuse here so the LLM is forced to add a constraint first.
    if (!(schemaField instanceof ControlledTermField))
      return error("field at '" + fieldPath + "' is not a controlled-term field "
          + "(schema class: " + schemaField.getClass().getSimpleName() + "). Add a "
          + "controlled-term constraint to the schema first via set_class_constraint / "
          + "set_ontology_constraint / set_branch_constraint / set_valueset_constraint.");

    FieldInputType inputType = schemaField.fieldUi().inputType();
    if (inputType != FieldInputType.TEXTFIELD)
      return error("controlled-term fields must have input type TEXTFIELD; got "
          + inputType);

    String finalPrefLabel = prefLabel;
    TemplateInstanceArtifact updated;
    try {
      updated = InstanceFieldValues.apply(instance, fieldPath, existing ->
          ControlledTermFieldInstance.builder()
              .withValue(iri)
              .withLabel(label)
              .withPreferredLabel(finalPrefLabel)
              .build());
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("set_controlled_term_field_value failed: "
          + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(updated);
    String yaml;
    try {
      yaml = ArtifactExchange.jsonNodeToYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated instance as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
        .isError(false)
        .build();
  }

  private static String stringArg(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

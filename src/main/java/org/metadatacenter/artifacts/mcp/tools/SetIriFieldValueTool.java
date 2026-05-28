package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.DoiFieldInstance;
import org.metadatacenter.artifacts.model.core.FieldInstanceArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.LinkFieldInstance;
import org.metadatacenter.artifacts.model.core.NihGrantIdFieldInstance;
import org.metadatacenter.artifacts.model.core.OrcidFieldInstance;
import org.metadatacenter.artifacts.model.core.PfasFieldInstance;
import org.metadatacenter.artifacts.model.core.PubMedFieldInstance;
import org.metadatacenter.artifacts.model.core.RorFieldInstance;
import org.metadatacenter.artifacts.model.core.RridFieldInstance;
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
 * MCP tool {@code set_iri_field_value} — sets the {@code @id} of an IRI-valued field
 * instance at a slash-separated {@code field_path}, with an optional human-readable
 * label.
 *
 * <p>IRI fields cover link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, and DOI —
 * anything whose instance carries an {@code @id} URI rather than a literal {@code @value}.
 * Controlled-term fields also carry an {@code @id}, but they have their own setter
 * ({@link SetControlledTermFieldValueTool}) because they additionally need a
 * {@code skos:prefLabel}.
 */
public final class SetIriFieldValueTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private SetIriFieldValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template JSON Schema the instance is based on. Used to look up the "
            + "field's input type at the given path."));
    properties.put("instance_json", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance JSON (the kind 'create_instance' or 'instance_from_yaml' returns)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the target field. Same syntax as 'set_field_value'."));
    properties.put("iri", Map.of(
        "type", "string",
        "description", "URI to set as the field's @id."));
    properties.put("label", Map.of(
        "type", "string",
        "description",
        "Optional human-readable label for the IRI (rdfs:label). Commonly supplied "
            + "alongside the URI when the LLM has resolved both."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("template_json", "instance_json", "field_path", "iri"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_iri_field_value")
        .title("Set an IRI-valued field on an instance")
        .description(
            "Sets the @id of an IRI-valued field instance (link, ROR, ORCID, PFAS, "
                + "RRID, PubMed, NIH-grant-ID, DOI) at a slash-separated field_path, "
                + "with an optional rdfs:label. Returns the updated instance JSON."
                + YamlVocabulary.YAML_PREFERRED_DISPLAY_NUDGE)
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

    URI iri;
    try {
      iri = new URI(iriArg);
    } catch (URISyntaxException e) {
      return error("iri is not a valid URI: " + e.getMessage());
    }

    String label = stringArg(args, "label");  // optional

    TemplateSchemaArtifact template;
    try {
      JsonNode parsedTemplate = JACKSON2.readTree(templateJsonText);
      if (!(parsedTemplate instanceof ObjectNode templateObject))
        return error("template_json must parse to a JSON object");
      template = READER.readTemplateSchemaArtifact(templateObject);
    } catch (ArtifactParseException e) {
      return error("template_json rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("template_json parse failed: " + e.getMessage());
    }

    TemplateInstanceArtifact instance;
    try {
      JsonNode parsedInstance = JACKSON2.readTree(instanceJsonText);
      if (!(parsedInstance instanceof ObjectNode instanceObject))
        return error("instance_json must parse to a JSON object");
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

    if (schemaField instanceof ControlledTermField)
      return error("field at '" + fieldPath + "' is a controlled-term field — use "
          + "set_controlled_term_field_value instead");

    FieldInputType inputType = schemaField.fieldUi().inputType();
    if (!inputType.isIri())
      return error("field at '" + fieldPath + "' has input type " + inputType
          + ", not an IRI type — use set_field_value or set_controlled_term_field_value");

    FieldInstanceArtifact newFieldInstance;
    try {
      newFieldInstance = buildIriFieldInstance(inputType, iri, label);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    TemplateInstanceArtifact updated;
    try {
      updated = InstanceFieldValues.apply(instance, fieldPath, existing -> newFieldInstance);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    } catch (RuntimeException e) {
      return error("set_iri_field_value failed: " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    ObjectNode rendered = RENDERER.renderTemplateInstanceArtifact(updated);
    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize updated instance: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static FieldInstanceArtifact buildIriFieldInstance(FieldInputType inputType, URI iri, String label)
  {
    return switch (inputType) {
      case LINK -> {
        LinkFieldInstance.LinkFieldInstanceBuilder b = LinkFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case ROR -> {
        RorFieldInstance.RorFieldInstanceBuilder b = RorFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case ORCID -> {
        OrcidFieldInstance.OrcidFieldInstanceBuilder b = OrcidFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case PFAS -> {
        PfasFieldInstance.PfasFieldInstanceBuilder b = PfasFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case RRID -> {
        RridFieldInstance.RridFieldInstanceBuilder b = RridFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case PUBMED -> {
        PubMedFieldInstance.PubMedFieldInstanceBuilder b = PubMedFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case NIH_GRANT_ID -> {
        NihGrantIdFieldInstance.NihGrantIdFieldInstanceBuilder b = NihGrantIdFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      case DOI -> {
        DoiFieldInstance.DoiFieldInstanceBuilder b = DoiFieldInstance.builder().withValue(iri);
        if (label != null && !label.isBlank()) b.withLabel(label);
        yield b.build();
      }
      default -> throw new IllegalArgumentException(
          "set_iri_field_value does not support input type " + inputType);
    };
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

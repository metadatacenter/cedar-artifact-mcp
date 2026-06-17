package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ControlledTermField;
import org.metadatacenter.artifacts.model.core.ControlledTermFieldInstance;
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
import org.metadatacenter.artifacts.model.tools.InstanceInflater;

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
 * <p>IRI fields cover link, ROR, ORCID, PFAS, RRID, PubMed, NIH-grant-ID, DOI, and
 * controlled-term — anything whose instance carries an {@code @id} URI rather than a
 * literal {@code @value}. The controlled-term case additionally requires the label
 * ({@code rdfs:label}); the schema
 * must already declare the field controlled-term (it carries a class/ontology/branch/
 * value-set constraint), because a TEXTFIELD without a constraint isn't classified as
 * ControlledTermField on JSON round-trip.
 */
public final class SetIriFieldValueTool
{
  private static final JsonArtifactReader READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private SetIriFieldValueTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", Map.of(
        "type", "string",
        "description",
        "CEDAR template the instance is based on, as YAML. Used to look up the "
            + "field's input type at the given path."));
    properties.put("instance", Map.of(
        "type", "string",
        "description",
        "CEDAR template instance as YAML (the kind 'create_template_instance' returns)."));
    properties.put("field_path", Map.of(
        "type", "string",
        "description",
        "Slash-separated path to the target field, same syntax as 'set_literal_field_value': "
            + "'patient_name', 'address/street', and a 0-based bracket index for repeatable "
            + "fields/elements ('tags[0]', 'addresses[2]/street'; an index equal to the current "
            + "length appends). Single-instance fields and elements take no index."));
    properties.put("iri", Map.of(
        "type", "string",
        "description", "URI to set as the field's @id."));
    properties.put("label", Map.of(
        "type", "string",
        "description",
        "Human-readable label for the IRI (rdfs:label). Optional for plain IRI fields "
            + "(commonly supplied alongside the URI when the LLM has resolved both); "
            + "required for controlled-term fields."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties,
        List.of("template", "instance", "field_path", "iri"),
        Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("set_iri_field_value")
        .title("Set an IRI-valued field on an instance")
        .description(
            "Sets the @id of an IRI-valued field instance (link, ROR, ORCID, PFAS, "
                + "RRID, PubMed, NIH-grant-ID, DOI, controlled-term) at a slash-separated "
                + "field_path. label (rdfs:label) is optional for plain IRI fields and "
                + "required for controlled-term fields. Returns the updated instance as "
                + "expanded YAML."
                + ArtifactExchange.VERBATIM_NOTICE + ArtifactExchange.DISPLAY_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    String templateJsonText = stringArg(args, "template");
    if (templateJsonText == null || templateJsonText.isBlank())
      return error("template is required and must not be blank");

    String instanceJsonText = stringArg(args, "instance");
    if (instanceJsonText == null || instanceJsonText.isBlank())
      return error("instance is required and must not be blank");

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

    String label = stringArg(args, "label");  // optional for plain IRI fields

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
      return error("template rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("template parse failed: " + e.getMessage());
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
      return error("instance rejected by reader: " + e.getMessage());
    } catch (Exception e) {
      return error("instance parse failed: " + e.getMessage());
    }

    // A YAML instance is sparse — unset fields are omitted. Inflate against the template so the
    // target slot exists before setting a value.
    try {
      instance = InstanceInflater.inflate(template, instance);
    } catch (RuntimeException e) {
      return error("instance does not match template (could not inflate): " + e.getMessage());
    }

    FieldSchemaArtifact schemaField;
    try {
      schemaField = SchemaPaths.resolveField(template, fieldPath);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    FieldInstanceArtifact newFieldInstance;
    if (schemaField instanceof ControlledTermField) {
      if (label == null || label.isBlank())
        return error("field at '" + fieldPath + "' is a controlled-term field — label "
            + "is required alongside the class IRI");
      FieldInputType inputType = schemaField.fieldUi().inputType();
      if (inputType != FieldInputType.TEXTFIELD)
        return error("controlled-term fields must have input type TEXTFIELD; got "
            + inputType);
      // @id + rdfs:label only — the shape the CEDAR editor writes. The model also
      // supports skos:prefLabel on a value, but editor-produced instances never carry it.
      newFieldInstance = ControlledTermFieldInstance.builder()
          .withValue(iri)
          .withLabel(label)
          .build();
    } else {
      FieldInputType inputType = schemaField.fieldUi().inputType();
      if (!inputType.isIri())
        return error("field at '" + fieldPath + "' has input type " + inputType
            + ", not an IRI type — use set_literal_field_value. If this field is meant "
            + "to be controlled-term, attach a constraint to the schema field first via "
            + "set_class_constraint / set_ontology_constraint / set_branch_constraint / "
            + "set_valueset_constraint.");
      try {
        newFieldInstance = buildIriFieldInstance(inputType, iri, label);
      } catch (IllegalArgumentException e) {
        return error(e.getMessage());
      }
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
    String yaml;
    try {
      yaml = ArtifactExchange.exchangeYaml(rendered);
    } catch (RuntimeException e) {
      return error("failed to render updated instance as YAML: " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, yaml)))
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

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementInstanceArtifact;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.InstanceInflater;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code render_instance_artifact} — renders a CEDAR instance (template instance or
 * element instance) to YAML or JSON. The input may be the YAML exchange form (what
 * {@code create_template_instance} / {@code create_element_instance} return) or a JSON instance
 * (what a CEDAR repository serves) — both the serialization and the kind are auto-detected.
 *
 * <p>{@code format} selects the output serialization: {@code yaml} (default) or pretty-printed
 * JSON. {@code compact} (YAML only, default true) selects the lean display form versus the
 * expanded, losslessly-round-tripping exchange form.
 *
 * <p>A YAML instance is sparse (unset fields omitted), whereas a complete CEDAR instance carries
 * every field its schema declares. When the optional {@code template_artifact} (the template or
 * element the instance is based on) is supplied, the instance is inflated against it via
 * {@link InstanceInflater} so the output is complete; otherwise only the fields the instance
 * actually carries are rendered. An instance that names no {@code @id} is rendered without one —
 * CEDAR assigns identity on create (DESIGN.md Principle 10). No validation runs here. Schema
 * artifacts are redirected to {@code render_schema_artifact}.
 */
public final class RenderInstanceArtifactTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private RenderInstanceArtifactTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("instance_artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR instance — a template instance (type: instance) or an element instance "
            + "(type: element-instance) — as YAML (the exchange form create_template_instance / "
            + "create_element_instance return) or as a JSON string (what a CEDAR repository serves "
            + "for a saved instance). Both the serialization and the kind are auto-detected. Full "
            + "key vocabulary and value-shape conventions:\n\n"
            + YamlVocabulary.instanceVocabulary()));
    properties.put("template_artifact", Map.of(
        "type", "string",
        "description",
        "The schema the instance is based on (YAML or JSON Schema) — a template for a template "
            + "instance, an element for an element instance. Optional but recommended when you need "
            + "a complete instance — a YAML instance is sparse (fields with no value are omitted), "
            + "whereas a complete CEDAR instance carries every field the schema declares. When "
            + "supplied, the instance is inflated against it so the output is complete (and will "
            + "validate); when omitted, only the fields the instance actually carries are rendered."));
    properties.put("format", Map.of(
        "type", "string",
        "enum", List.of("yaml", "json"),
        "default", "yaml",
        "description",
        "Output serialization. 'yaml' (default) renders YAML — the compact form these tools read, "
            + "write, display, and exchange. 'json' renders pretty-printed CEDAR JSON, the export "
            + "escape hatch for the narrow case where a downstream CEDAR tool or service cannot "
            + "consume YAML; JSON is far larger than YAML."));
    properties.put("compact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean compact form (YAML only). true (default) is the lean display "
            + "form; false emits the expanded, losslessly-round-tripping exchange form. Applies "
            + "only to YAML output; pairing compact: true with format: json is an error."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("instance_artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("render_instance_artifact")
        .title("Render a CEDAR instance as YAML or JSON (auto-detect template/element instance)")
        .description(
            "Primary rendering path for CEDAR instances. Renders a CEDAR template instance or "
                + "element instance (auto-detected; YAML or JSON) to YAML (default) or JSON. "
                + "'format: yaml' produces the compact form these tools read, write, display, and "
                + "exchange; 'compact' selects compact (lean, default) or expanded full-fidelity "
                + "YAML. 'format: json' produces CEDAR JSON — an export escape hatch for the narrow "
                + "case where a downstream tool cannot consume YAML. Supply the optional "
                + "template_artifact (the template or element it is based on) to inflate the sparse "
                + "instance to a complete instance; omit it to render only the fields present. No "
                + "validation runs here; validate separately with validate_instance_artifact. (For "
                + "a standalone template, element, or field — a schema, not an instance — use "
                + "render_schema_artifact.)"
                + ArtifactExchange.VERBATIM_NOTICE)
        .inputSchema(schema)
        .build();
  }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawInstance = args.get("instance_artifact");
    if (rawInstance == null)
      return error("instance_artifact argument is required");
    String instanceText = rawInstance.toString();
    if (instanceText.isBlank())
      return error("instance_artifact argument must not be blank");

    boolean asYaml;
    try {
      asYaml = parseFormat(args.get("format"));
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    boolean compact;
    try {
      // The render-tool default is compact: true, but only for YAML output — defaulting it on for
      // JSON would trip requireCompactCompatibleWith. An explicit compact is honored either way.
      compact = args.get("compact") == null ? asYaml : ArtifactFiles.compactFlag(args.get("compact"));
      ArtifactFiles.requireCompactCompatibleWith(asYaml, compact);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    Object rawTemplate = args.get("template_artifact");
    String templateText =
        rawTemplate == null || rawTemplate.toString().isBlank() ? null : rawTemplate.toString();

    boolean isElement;
    try {
      isElement = ArtifactExchange.isElementInstance(instanceText);
    } catch (RuntimeException e) {
      return error("instance_artifact could not be parsed as a CEDAR instance (YAML or JSON): "
          + e.getMessage());
    }

    // Reject a schema artifact (template/element/field) up front — it is not an instance.
    McpSchema.CallToolResult redirect = rejectIfSchemaArtifact(instanceText);
    if (redirect != null)
      return redirect;

    boolean inputIsJson = instanceText.stripLeading().startsWith("{");

    org.metadatacenter.artifacts.model.core.Artifact instance;
    try {
      if (isElement) {
        ElementInstanceArtifact el = readElementInstance(instanceText, inputIsJson);
        if (templateText != null) {
          ElementSchemaArtifact element = ArtifactExchange.readElement(templateText);
          el = InstanceInflater.inflateElement(element, el);
        }
        instance = el;
      } else {
        TemplateInstanceArtifact ti = readTemplateInstance(instanceText, inputIsJson);
        if (templateText != null) {
          TemplateSchemaArtifact template = ArtifactExchange.readTemplate(templateText);
          ti = InstanceInflater.inflate(template, ti);
        }
        instance = ti;
      }
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      if (templateText != null)
        return error("template_artifact supplied but the instance could not be inflated against it "
            + "(is it the right template/element?): " + e.getMessage());
      return error("could not read the input as a CEDAR instance — if this is a standalone "
          + "template, element, or field (a schema artifact, not an instance), use "
          + "render_schema_artifact instead. Reader said: " + e.getMessage());
    }

    if (asYaml) {
      String yaml;
      try {
        yaml = ArtifactExchange.toYaml(instance, compact);
      } catch (RuntimeException e) {
        return error("YAML rendering failed: " + e.getMessage());
      }
      if (yaml == null)
        return error("YAML rendering returned null");
      return success(yaml);
    }

    ObjectNode rendered = isElement
        ? RENDERER.renderElementInstanceArtifact((ElementInstanceArtifact) instance)
        : RENDERER.renderTemplateInstanceArtifact((TemplateInstanceArtifact) instance);
    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered instance: " + e.getMessage());
    }
    return success(json);
  }

  // Helpers reading the instance from its serialization.

  private static TemplateInstanceArtifact readTemplateInstance(String text, boolean inputIsJson)
  {
    if (inputIsJson)
      return ArtifactExchange.readInstance(text);
    return ArtifactExchange.readTemplateInstanceYaml(ArtifactExchange.parseYamlMap(text));
  }

  private static ElementInstanceArtifact readElementInstance(String text, boolean inputIsJson)
  {
    if (inputIsJson)
      return ArtifactExchange.readElementInstance(text);
    return ArtifactExchange.readElementInstanceYaml(ArtifactExchange.parseYamlMap(text));
  }


  /**
   * Returns a redirect error when the input is a schema artifact (template/element/field) rather
   * than an instance, otherwise {@code null}. A schema artifact is recognized by a schema-artifact
   * top-level {@code type:} (YAML) or {@code @type} (JSON).
   */
  private static McpSchema.CallToolResult rejectIfSchemaArtifact(String text)
  {
    boolean isSchema;
    try {
      if (text.stripLeading().startsWith("{")) {
        ObjectNode node = ArtifactExchange.toObjectNode(text);
        ArtifactKinds.Kind kind = ArtifactKinds.detect(node);
        isSchema = kind == ArtifactKinds.Kind.TEMPLATE
            || kind == ArtifactKinds.Kind.ELEMENT
            || kind == ArtifactKinds.Kind.FIELD;
      } else {
        String type = String.valueOf(ArtifactExchange.parseYamlMap(text).get("type"));
        isSchema = switch (type) {
          case "template", "element" -> true;
          case "instance", "element-instance" -> false;
          // Any other discriminator is a field kind (a schema artifact).
          default -> true;
        };
      }
    } catch (RuntimeException e) {
      return null; // Let the main reader path produce the diagnostic.
    }
    if (isSchema)
      return error("this is a schema artifact (template, element, or field), not an instance — "
          + "use render_schema_artifact to render it");
    return null;
  }

  /** Parse the optional {@code format} string: yaml/yml → YAML, json → JSON, absent → YAML. */
  private static boolean parseFormat(Object raw)
  {
    if (raw == null)
      return true;
    String format = raw.toString().trim().toLowerCase();
    if (format.isEmpty() || format.equals("yaml") || format.equals("yml"))
      return true;
    if (format.equals("json"))
      return false;
    throw new IllegalArgumentException("format must be 'yaml' or 'json' (got '" + raw + "')");
  }

  private static McpSchema.CallToolResult success(String text)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, text)))
        .isError(false)
        .build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.model.ModelNodeNames;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code render_schema_artifact} — renders a CEDAR schema artifact (template, element, or
 * field) to YAML or JSON. The input may be the YAML authoring/exchange form or a JSON Schema — both
 * the serialization and the kind are auto-detected from the document.
 *
 * <p>{@code format} selects the output serialization: {@code yaml} (default) emits YAML;
 * {@code json} emits pretty-printed CEDAR JSON Schema. {@code compact} (YAML only, default true)
 * selects the lean display form (provenance, status, version, {@code modelVersion} omitted) versus
 * the expanded exchange form that round-trips losslessly.
 *
 * <p>An artifact that names no {@code @id} is rendered without one — CEDAR assigns identity on
 * create (DESIGN.md Principle 10). No CedarValidator step runs — rendering renders; validation lives in
 * {@code validate_schema_artifact}. Instances are not schema artifacts — a {@code type: instance} or
 * {@code type: element-instance} document is redirected to {@code render_instance_artifact}.
 */
public final class RenderSchemaArtifactTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  // Compact-mode reader: tolerant of the lean authoring YAML the LLM produces.
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();

  private RenderSchemaArtifactTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("schema_artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR schema artifact — template, element, or field — as YAML (the authoring/exchange "
            + "form the create/add/set tools return) or as a JSON Schema string. Both the "
            + "serialization and the kind are auto-detected from the top-level 'type:' (YAML) or "
            + "'@type' (JSON) — 'template', 'element', or a field discriminator (text-field, "
            + "numeric-field, controlled-term-field, ...). For templates and elements the "
            + "'children:' list carries the field/element specifications. Full key vocabulary:\n\n"
            + YamlVocabulary.fullSchemaVocabulary()));
    properties.put("format", Map.of(
        "type", "string",
        "enum", List.of("yaml", "json"),
        "default", "yaml",
        "description",
        "Output serialization. 'yaml' (default) renders YAML — the compact form these tools read, "
            + "write, display, and exchange. 'json' renders pretty-printed CEDAR JSON Schema, the "
            + "export escape hatch for the narrow case where a downstream CEDAR tool or service "
            + "cannot consume YAML; JSON Schema is far larger than YAML."));
    properties.put("compact", Map.of(
        "type", "boolean",
        "default", Boolean.TRUE,
        "description",
        "Whether to emit the lean, LLM-friendly compact form (YAML only). true (default) omits "
            + "provenance, status, version, and modelVersion — the round-trip back to a model "
            + "defaults the absent modelVersion, so it reads cleanly. false emits the expanded "
            + "exchange form (every field the renderer can produce), which round-trips losslessly. "
            + "Applies only to YAML output; pairing compact: true with format: json is an error."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("schema_artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("render_schema_artifact")
        .title("Render a CEDAR schema artifact as YAML or JSON (auto-detect template/element/field)")
        .description(
            "Primary rendering path for CEDAR schema artifacts. Renders a CEDAR template, element, "
                + "or field (a schema artifact; YAML or JSON Schema) to YAML (default) or JSON. The "
                + "kind is auto-detected. 'format: yaml' produces the compact form these tools "
                + "read, write, display, and exchange; 'compact' selects compact (lean, default) "
                + "or expanded full-fidelity YAML. 'format: json' produces CEDAR JSON Schema — an "
                + "export escape hatch for the narrow case where a downstream tool cannot consume "
                + "YAML. No validation runs here; validate separately with validate_schema_artifact. "
                + "(To render a template instance or element instance — an instance, not a schema — "
                + "use render_instance_artifact.)"
                + ArtifactExchange.VERBATIM_NOTICE)
        .inputSchema(schema)
        .build();
  }

  private enum Kind { TEMPLATE, ELEMENT, FIELD }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawArtifact = args.get("schema_artifact");
    if (rawArtifact == null)
      return error("schema_artifact argument is required");
    String artifactText = rawArtifact.toString();
    if (artifactText.isBlank())
      return error("schema_artifact argument must not be blank");

    boolean asYaml;
    try {
      asYaml = parseFormat(args.get("format"));
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    boolean compact;
    try {
      // The render-tool default is compact: true (the lean display form), but only for YAML
      // output — defaulting it on for JSON would trip requireCompactCompatibleWith. When the
      // caller passes compact explicitly, honor it (and let the JSON guard reject compact+json).
      compact = args.get("compact") == null ? asYaml : ArtifactFiles.compactFlag(args.get("compact"));
      ArtifactFiles.requireCompactCompatibleWith(asYaml, compact);
    } catch (IllegalArgumentException e) {
      return error(e.getMessage());
    }

    // JSON input — render straight through ArtifactExchange (auto-detects kind and instance).
    if (artifactText.stripLeading().startsWith("{"))
      return renderJsonInput(artifactText, asYaml, compact);

    LinkedHashMap<String, Object> yamlMap;
    try {
      yamlMap = ArtifactExchange.parseYamlMap(artifactText);
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

    String type = yamlMap.get("type") == null ? "" : String.valueOf(yamlMap.get("type"));
    Kind kind;
    switch (type) {
      case "template" -> kind = Kind.TEMPLATE;
      case "element" -> kind = Kind.ELEMENT;
      case "instance", "element-instance" ->
          { return error("this is an instance, not a schema artifact — use "
              + "render_instance_artifact to render it"); }
      // Every other discriminator is a field kind (text-field, numeric-field, ...).
      default -> kind = Kind.FIELD;
    }

    ObjectNode rendered;
    try {
      rendered = switch (kind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact t = ArtifactExchange.readTemplateSchemaYaml(yamlMap);
          yield RENDERER.renderTemplateSchemaArtifact(t);
        }
        case ELEMENT -> {
          ElementSchemaArtifact el = ArtifactExchange.readElementSchemaYaml(yamlMap);
          yield RENDERER.renderElementSchemaArtifact(el);
        }
        case FIELD -> {
          FieldSchemaArtifact f = ArtifactExchange.readFieldSchemaYaml(yamlMap);
          yield RENDERER.renderFieldSchemaArtifact(f);
        }
      };
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error(kind.name().toLowerCase() + " reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    if (asYaml) {
      String yaml;
      try {
        yaml = ArtifactExchange.jsonNodeToYaml(rendered, compact);
      } catch (RuntimeException e) {
        return error("YAML rendering failed: " + e.getMessage());
      }
      if (yaml == null)
        return error("YAML rendering returned null");
      return success(yaml);
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered " + kind.name().toLowerCase() + ": " + e.getMessage());
    }
    return success(json);
  }

  /**
   * Render a JSON-Schema input. The kind is auto-detected from {@code @type}; an instance is
   * redirected. An already-JSON artifact carries its own {@code @id}, so no minting is needed.
   */
  private static McpSchema.CallToolResult renderJsonInput(
      String artifactText, boolean asYaml, boolean compact)
  {
    ObjectNode node;
    try {
      node = ArtifactExchange.toObjectNode(artifactText);
    } catch (RuntimeException e) {
      return error("schema_artifact could not be parsed as a CEDAR schema artifact (JSON): "
          + e.getMessage());
    }

    ArtifactKinds.Kind kind = ArtifactKinds.detect(node);
    if (kind == ArtifactKinds.Kind.INSTANCE || kind == null)
      return error("this is an instance, not a schema artifact — use "
          + "render_instance_artifact to render it");

    if (!asYaml) {
      try {
        return success(JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(node));
      } catch (Exception e) {
        return error("failed to serialize rendered schema artifact: " + e.getMessage());
      }
    }
    String yaml;
    try {
      yaml = ArtifactExchange.jsonNodeToYaml(node, compact);
    } catch (RuntimeException e) {
      return error("YAML rendering failed: " + e.getMessage());
    }
    if (yaml == null)
      return error("YAML rendering returned null");
    return success(yaml);
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

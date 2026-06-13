package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.model.core.ElementSchemaArtifact;
import org.metadatacenter.artifacts.model.core.FieldSchemaArtifact;
import org.metadatacenter.artifacts.model.core.TemplateSchemaArtifact;
import org.metadatacenter.artifacts.model.reader.ArtifactParseException;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.yaml.YamlConstants;
import org.metadatacenter.model.validation.CedarValidator;
import org.metadatacenter.model.validation.ModelValidator;
import org.metadatacenter.model.validation.report.ErrorItem;
import org.metadatacenter.model.validation.report.ValidationReport;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool {@code schema_artifact_to_json} — compiles a CEDAR schema artifact described in the
 * artifact library's YAML authoring form into the canonical CEDAR JSON Schema. The kind is
 * auto-detected from the YAML {@code type:} discriminator: {@code template}, {@code element}, or
 * any field discriminator (a field is the default). The matching pipeline runs — a missing
 * top-level {@code @id} is minted with the kind's IRI prefix (DESIGN.md Principle 10), the YAML is
 * read into the model, and the rendered JSON is validated with the kind's {@link CedarValidator}
 * method (DESIGN.md Principle 6) before returning, so a non-error result is a guaranteed-valid
 * CEDAR artifact.
 *
 * <p>Instances are not schema artifacts: a {@code type: instance} or {@code type: element-instance}
 * document is redirected to {@code instance_artifact_to_json}.
 */
public final class SchemaArtifactToJsonTool
{
  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  // Compact-mode reader: tolerant of the lean authoring YAML the LLM produces.
  private static final YamlArtifactReader READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer RENDERER = new JsonArtifactRenderer();
  private static final ModelValidator VALIDATOR = new CedarValidator();

  private SchemaArtifactToJsonTool() {}

  public static McpSchema.Tool tool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("artifact", Map.of(
        "type", "string",
        "description",
        "A CEDAR schema artifact described in the artifact library's YAML format. The kind is "
            + "auto-detected from the top-level 'type:' — 'template', 'element', or a field "
            + "discriminator (text-field, numeric-field, controlled-term-field, ...). For "
            + "templates and elements the 'children:' list carries the field/element "
            + "specifications. Full key vocabulary:\n\n"
            + YamlVocabulary.fullSchemaVocabulary()));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("artifact"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("schema_artifact_to_json")
        .title("CEDAR schema artifact: YAML → JSON Schema (auto-detect template/element/field)")
        .description(
            "Compiles a CEDAR template, element, or field (a schema artifact) described in YAML "
                + "(the compact authoring form) into the canonical CEDAR JSON Schema that "
                + "downstream CEDAR tooling consumes. The kind is auto-detected from the YAML "
                + "'type:' and the matching reader/renderer/validator runs. The returned JSON has "
                + "been round-tripped through the artifact library and accepted by CedarValidator, "
                + "so a non-error result is a guaranteed-valid CEDAR artifact. (To render a "
                + "template instance or element instance — an instance, not a schema — use "
                + "instance_artifact_to_json.)")
        .inputSchema(schema)
        .build();
  }

  private enum Kind { TEMPLATE, ELEMENT, FIELD }

  public static McpSchema.CallToolResult handler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();

    Object rawYaml = args.get("artifact");
    if (rawYaml == null)
      return error("artifact argument is required");
    String yamlText = rawYaml.toString();
    if (yamlText.isBlank())
      return error("artifact argument must not be blank");

    LinkedHashMap<String, Object> yamlMap;
    try {
      yamlMap = ArtifactExchange.parseYamlMap(yamlText);
    } catch (RuntimeException e) {
      return error("YAML parse failed: " + e.getMessage());
    }

    String type = yamlMap.get("type") == null ? "" : String.valueOf(yamlMap.get("type"));
    Kind kind;
    URI mintedId;
    switch (type) {
      case "template" -> { kind = Kind.TEMPLATE; mintedId = IdMinter.mintTemplateId(); }
      case "element" -> { kind = Kind.ELEMENT; mintedId = IdMinter.mintElementId(); }
      case "instance", "element-instance" ->
          { return error("this is an instance, not a schema artifact — use "
              + "instance_artifact_to_json to render it"); }
      // Every other discriminator is a field kind (text-field, numeric-field, ...).
      default -> { kind = Kind.FIELD; mintedId = IdMinter.mintFieldId(); }
    }

    // Mint a top-level @id when the YAML omits one (DESIGN.md Principle 10). Only the top-level
    // map is touched; nested children under 'children:' are never given an id.
    Object suppliedId = yamlMap.get(YamlConstants.ID);
    if (suppliedId == null || suppliedId.toString().isBlank())
      yamlMap.put(YamlConstants.ID, mintedId.toString());

    ObjectNode rendered;
    try {
      rendered = switch (kind) {
        case TEMPLATE -> {
          TemplateSchemaArtifact t = READER.readTemplateSchemaArtifact(yamlMap);
          yield RENDERER.renderTemplateSchemaArtifact(t);
        }
        case ELEMENT -> {
          ElementSchemaArtifact el = READER.readElementSchemaArtifact(yamlMap);
          yield RENDERER.renderElementSchemaArtifact(el);
        }
        case FIELD -> {
          FieldSchemaArtifact f = READER.readFieldSchemaArtifact(yamlMap);
          yield RENDERER.renderFieldSchemaArtifact(f);
        }
      };
    } catch (ArtifactParseException e) {
      return error("CEDAR YAML rejected by reader: " + e.getMessage());
    } catch (RuntimeException e) {
      return error(kind.name().toLowerCase() + " reader threw " + e.getClass().getSimpleName()
          + ": " + e.getMessage());
    }

    try {
      ValidationReport report = switch (kind) {
        case TEMPLATE -> VALIDATOR.validateTemplate(rendered);
        case ELEMENT -> VALIDATOR.validateTemplateElement(rendered);
        case FIELD -> VALIDATOR.validateTemplateField(rendered);
      };
      if (!"true".equals(report.getValidationStatus()))
        return error("rendered " + kind.name().toLowerCase()
            + " failed CedarValidator: " + formatErrors(report));
    } catch (Exception e) {
      return error("CedarValidator threw while validating rendered " + kind.name().toLowerCase()
          + ": " + e.getMessage());
    }

    String json;
    try {
      json = JACKSON2.writerWithDefaultPrettyPrinter().writeValueAsString(rendered);
    } catch (Exception e) {
      return error("failed to serialize rendered " + kind.name().toLowerCase() + ": " + e.getMessage());
    }

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, json)))
        .isError(false)
        .build();
  }

  private static String formatErrors(ValidationReport report)
  {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (ErrorItem err : report.getErrors()) {
      if (i++ > 0) sb.append("; ");
      sb.append(err.toString());
      if (i >= 5) {
        sb.append("; ... (").append(report.getErrors().size() - i).append(" more)");
        break;
      }
    }
    return sb.length() == 0 ? "(no error details)" : sb.toString();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}

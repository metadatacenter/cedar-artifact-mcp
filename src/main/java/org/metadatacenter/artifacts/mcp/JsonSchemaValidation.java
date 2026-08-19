package org.metadatacenter.artifacts.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JSON Schema validator the MCP server checks a tool's structured output with, built on the
 * json-schema-validator release this project already carries.
 *
 * <p>The SDK ships its own and loads it through {@code ServiceLoader} when a server is built
 * without one. That default needs json-schema-validator 3.x, whose API dropped classes
 * {@code cedar-model-validation-library}'s {@code CedarValidator} calls, and only one version of a
 * library can be on a classpath: taking the SDK's default costs the CEDAR validator, and keeping
 * the CEDAR validator leaves the SDK's default unable to load. Supplying this one means the SDK
 * never asks for its own, so the 1.x release both this and {@code CedarValidator} compile against
 * is the only one present. Its behaviour matches the default's: the content is serialized back on
 * success, and the validation messages are reported verbatim on failure.
 */
public final class JsonSchemaValidation implements JsonSchemaValidator
{
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private final ConcurrentHashMap<String, com.networknt.schema.JsonSchema> schemaCache =
      new ConcurrentHashMap<>();

  @Override public ValidationResponse validate(Map<String, Object> schema, Object structuredContent)
  {
    if (schema == null)
      throw new IllegalArgumentException("Schema must not be null");
    if (structuredContent == null)
      throw new IllegalArgumentException("Structured content must not be null");

    try {
      JsonNode content = MAPPER.valueToTree(structuredContent);
      Set<ValidationMessage> errors = schemaFor(schema).validate(content);
      return errors.isEmpty()
          ? ValidationResponse.asValid(content.toString())
          : ValidationResponse.asInvalid(String.valueOf(errors));
    } catch (RuntimeException e) {
      return ValidationResponse.asInvalid("Failed to validate the tool result: " + e.getMessage());
    }
  }

  private com.networknt.schema.JsonSchema schemaFor(Map<String, Object> schema)
  {
    JsonNode schemaNode = MAPPER.valueToTree(schema);
    return schemaCache.computeIfAbsent(schemaNode.toString(), key -> FACTORY.getSchema(schemaNode));
  }
}

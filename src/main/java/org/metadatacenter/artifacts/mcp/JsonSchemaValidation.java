package org.metadatacenter.artifacts.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JSON Schema validator the MCP server uses, built on the json-schema-validator release this
 * project already carries. The SDK calls it to check each tool's input schema against the JSON
 * Schema 2020-12 meta-schema when the server is built, and to check every call's arguments against
 * that schema before the handler runs.
 *
 * <p>The SDK ships its own and loads it through {@code ServiceLoader} when a server is built
 * without one. That default needs json-schema-validator 3.x, whose API dropped classes
 * {@code cedar-model-validation-library}'s {@code CedarValidator} calls, and only one version of a
 * library can be on a classpath: taking the SDK's default costs the CEDAR validator, and keeping
 * the CEDAR validator leaves the SDK's default unable to load. Supplying this one means the SDK
 * never asks for its own, so the 1.x release both this and {@code CedarValidator} compile against
 * is the only one present. Its behaviour matches the default's: content given as a string is read
 * as JSON, the content is serialized back on success, the validation messages are reported
 * verbatim on failure, and a schema that declares a dialect other than 2020-12 is not checked
 * against the 2020-12 meta-schema.
 */
public final class JsonSchemaValidation implements JsonSchemaValidator
{
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private static final com.networknt.schema.JsonSchema META_SCHEMA_2020_12 =
      FACTORY.getSchema(SchemaLocation.of(McpSchema.JSON_SCHEMA_DIALECT_2020_12));

  private final ConcurrentHashMap<String, com.networknt.schema.JsonSchema> schemaCache =
      new ConcurrentHashMap<>();

  @Override public ValidationResponse validate(Map<String, Object> schema, Object structuredContent)
  {
    if (schema == null)
      throw new IllegalArgumentException("Schema must not be null");
    if (structuredContent == null)
      throw new IllegalArgumentException("Structured content must not be null");

    try {
      JsonNode content = structuredContent instanceof String text
          ? MAPPER.readTree(text)
          : MAPPER.valueToTree(structuredContent);
      Set<ValidationMessage> errors = schemaFor(schema).validate(content);
      return errors.isEmpty()
          ? ValidationResponse.asValid(content.toString())
          : ValidationResponse.asInvalid("Validation failed: JSON schema validation errors: " + errors);
    } catch (JsonProcessingException e) {
      return ValidationResponse.asInvalid("Error parsing tool JSON Schema: " + e.getOriginalMessage());
    } catch (RuntimeException e) {
      return ValidationResponse.asInvalid("Unexpected validation error: " + e.getMessage());
    }
  }

  @Override public ValidationResponse validateSchema(Map<String, Object> schema)
  {
    if (schema == null)
      throw new IllegalArgumentException("Schema must not be null");
    Object dialect = schema.get("$schema");
    if (dialect != null && !McpSchema.JSON_SCHEMA_DIALECT_2020_12.equals(dialect.toString()))
      return ValidationResponse.asValid(null);

    try {
      Set<ValidationMessage> errors = META_SCHEMA_2020_12.validate(MAPPER.valueToTree(schema));
      return errors.isEmpty()
          ? ValidationResponse.asValid(null)
          : ValidationResponse.asInvalid(
              "Schema does not conform to JSON Schema 2020-12 (SEP-1613): " + errors);
    } catch (RuntimeException e) {
      return ValidationResponse.asInvalid("Failed to validate schema definition: " + e.getMessage());
    }
  }

  private com.networknt.schema.JsonSchema schemaFor(Map<String, Object> schema)
  {
    JsonNode schemaNode = MAPPER.valueToTree(schema);
    return schemaCache.computeIfAbsent(schemaNode.toString(), key -> FACTORY.getSchema(schemaNode));
  }
}

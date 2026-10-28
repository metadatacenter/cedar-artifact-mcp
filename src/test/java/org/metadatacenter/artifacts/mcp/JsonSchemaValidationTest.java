package org.metadatacenter.artifacts.mcp;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.mcp.tools.ToolSchemas;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validator this server supplies in place of the SDK's default must judge what the default
 * judges: tool schemas against the JSON Schema 2020-12 meta-schema, and call arguments against a
 * tool's schema.
 */
final class JsonSchemaValidationTest
{
  private static final JsonSchemaValidation VALIDATOR = new JsonSchemaValidation();
  private static final Map<String, Object> NAME_ONLY = ToolSchemas.object(
      Map.of("name", Map.of("type", "string")), List.of("name"));

  @Test void a_tool_schema_conforms_to_2020_12()
  {
    assertTrue(VALIDATOR.validateSchema(NAME_ONLY).valid());
  }

  @Test void a_malformed_schema_is_refused()
  {
    ValidationResponse response = VALIDATOR.validateSchema(Map.of("type", "objekt", "required", "name"));

    assertFalse(response.valid());
    assertTrue(response.errorMessage().contains("2020-12"), response.errorMessage());
  }

  @Test void a_schema_in_another_dialect_is_not_checked_against_2020_12()
  {
    Map<String, Object> draft7 = Map.of(
        "$schema", "http://json-schema.org/draft-07/schema#", "type", "objekt");

    assertTrue(VALIDATOR.validateSchema(draft7).valid());
  }

  @Test void arguments_that_match_the_schema_are_accepted()
  {
    assertTrue(VALIDATOR.validate(NAME_ONLY, Map.of("name", "Study")).valid());
  }

  @Test void an_undeclared_argument_is_refused()
  {
    ValidationResponse response = VALIDATOR.validate(NAME_ONLY, Map.of("name", "Study", "colour", "red"));

    assertFalse(response.valid());
    assertTrue(response.errorMessage().contains("colour"), response.errorMessage());
  }

  @Test void a_missing_required_argument_is_refused()
  {
    assertFalse(VALIDATOR.validate(NAME_ONLY, Map.of()).valid());
  }

  @Test void content_given_as_a_string_is_read_as_json()
  {
    assertTrue(VALIDATOR.validate(NAME_ONLY, "{\"name\": \"Study\"}").valid());
    assertFalse(VALIDATOR.validate(NAME_ONLY, "{\"name\": 7}").valid());
  }
}

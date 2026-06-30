package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.core.fields.InputTimeFormat;
import org.metadatacenter.artifacts.model.core.fields.TemporalGranularity;
import org.metadatacenter.artifacts.model.core.fields.XsdNumericDatatype;
import org.metadatacenter.artifacts.model.core.fields.XsdTemporalDatatype;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards that create_field's advertised schema enums for {@code datatype}, {@code granularity}, and
 * {@code input_time_format} stay in lockstep with the library. Each advertised value must equal a
 * library {@code getText()} wire form and must round-trip through the handler's {@code fromString()},
 * so the schema clients validate against can never drift from what the handler accepts.
 */
final class CreateFieldSchemaEnumTest
{
  private static final ObjectMapper JACKSON = new ObjectMapper();

  /** The {@code enum} advertised for a property, read from the serialized input schema. */
  private static Set<String> advertisedEnum(String property)
  {
    JsonNode schema = JACKSON.valueToTree(CreateFieldTool.tool().inputSchema());
    JsonNode en = schema.path("properties").path(property).path("enum");
    assertTrue(en.isArray() && en.size() > 0, "no enum advertised for '" + property + "'");
    Set<String> values = new LinkedHashSet<>();
    en.forEach(v -> values.add(v.asText()));
    return values;
  }

  @Test void datatype_enum_equals_library_wire_forms()
  {
    Set<String> expected = Stream.concat(
            Arrays.stream(XsdNumericDatatype.values()).map(XsdNumericDatatype::getText),
            Arrays.stream(XsdTemporalDatatype.values()).map(XsdTemporalDatatype::getText))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    assertEquals(expected, advertisedEnum("datatype"));
  }

  @Test void datatype_enum_round_trips_through_fromString()
  {
    for (String v : advertisedEnum("datatype")) {
      try {
        XsdNumericDatatype.fromString(v);
      } catch (Exception numeric) {
        try {
          XsdTemporalDatatype.fromString(v);
        } catch (Exception temporal) {
          fail("advertised datatype '" + v + "' is accepted by neither numeric nor temporal fromString()");
        }
      }
    }
  }

  @Test void granularity_enum_matches_and_round_trips()
  {
    Set<String> expected = Arrays.stream(TemporalGranularity.values())
        .map(TemporalGranularity::getText).collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> advertised = advertisedEnum("granularity");
    assertEquals(expected, advertised);
    for (String v : advertised)
      try { TemporalGranularity.fromString(v); }
      catch (Exception e) { fail("advertised granularity '" + v + "' rejected by fromString(): " + e); }
  }

  @Test void input_time_format_enum_matches_and_round_trips()
  {
    Set<String> expected = Arrays.stream(InputTimeFormat.values())
        .map(InputTimeFormat::getText).collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> advertised = advertisedEnum("input_time_format");
    assertEquals(expected, advertised);
    for (String v : advertised)
      try { InputTimeFormat.fromString(v); }
      catch (Exception e) { fail("advertised input_time_format '" + v + "' rejected by fromString(): " + e); }
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared assertions for auto-minted CEDAR {@code @id} IRIs (DESIGN.md Principle 10).
 *
 * <p>A minted id has the form {@code https://repo.metadatacenter.org/<collection>/<uuid>},
 * where {@code <collection>} is one of {@code templates}, {@code template-elements}, or
 * {@code template-fields}, and {@code <uuid>} is a random UUID.
 */
final class MintedIds
{
  private static final String BASE = "https://repo.metadatacenter.org/";

  private MintedIds() {}

  /** Asserts {@code idNode} is a freshly-minted id under the given collection. */
  static void assertMintedId(JsonNode idNode, String collection)
  {
    assertNotNull(idNode, "@id node should be present");
    assertTrue(idNode.isTextual(), "@id should be a string; got: " + idNode);
    assertMintedId(idNode.asText(), collection);
  }

  /** Asserts {@code id} is a freshly-minted id string under the given collection. */
  static void assertMintedId(String id, String collection)
  {
    assertNotNull(id, "id should be present");
    String prefix = BASE + collection + "/";
    assertTrue(id.startsWith(prefix),
        "@id should start with " + prefix + "; got: " + id);
    String suffix = id.substring(prefix.length());
    try {
      UUID.fromString(suffix);
    } catch (IllegalArgumentException e) {
      fail("@id suffix should be a UUID; got: " + suffix);
    }
  }

  /** Asserts {@code idNode} carries no id — either absent or JSON null. */
  static void assertNoId(JsonNode idNode, String what)
  {
    assertTrue(idNode == null || idNode.isMissingNode() || idNode.isNull(),
        what + " should have no @id (top-level minting only); got: " + idNode);
  }
}

package org.metadatacenter.artifacts.mcp.tools;

import java.net.URI;
import java.util.UUID;

/**
 * Mints CEDAR {@code @id} IRIs for the top-level schema artifacts this MCP creates when the
 * caller supplies none.
 *
 * <p>The minted form mirrors what a CEDAR repository assigns and what the tool descriptions
 * tell callers to hand-mint: the per-artifact base under
 * {@code https://repo.metadatacenter.org/} plus a fresh random UUID. Each artifact kind has
 * its own base — templates, elements, fields, and instances are distinct collections in
 * CEDAR.
 *
 * <p>This is a deliberate MCP-layer convenience (DESIGN.md Principle 10). The artifact
 * library's model leaves {@code @id} optional; only this LLM-facing layer fills an absent one
 * in, and only for the top-level artifact a tool returns — never for nested children.
 */
public final class IdMinter
{
  private static final String BASE = "https://repo.metadatacenter.org/";

  private IdMinter() {}

  public static URI mintTemplateId()
  {
    return mint("templates");
  }

  public static URI mintElementId()
  {
    return mint("template-elements");
  }

  public static URI mintFieldId()
  {
    return mint("template-fields");
  }

  public static URI mintInstanceId()
  {
    return mint("template-instances");
  }

  private static URI mint(String collection)
  {
    return URI.create(BASE + collection + "/" + UUID.randomUUID());
  }
}

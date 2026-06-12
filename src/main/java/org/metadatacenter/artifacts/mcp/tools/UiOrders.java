package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Helper for the {@code replace_*} tools. The library's parent builders only ever append
 * to {@code _ui.order} — there is no insert-at-position primitive — so replacing a child
 * via remove + re-add moves its key to the end of the display order. This puts the key
 * back where it was, operating on the rendered JSON.
 */
final class UiOrders
{
  private UiOrders() {}

  static void restorePosition(ObjectNode rendered, String key, int originalIndex)
  {
    if (originalIndex < 0)
      return;
    JsonNode ui = rendered.path("_ui");
    if (!(ui instanceof ObjectNode))
      return;
    JsonNode order = ui.path("order");
    if (!(order instanceof ArrayNode orderNode))
      return;
    int current = -1;
    for (int i = 0; i < orderNode.size(); i++) {
      if (key.equals(orderNode.get(i).asText())) {
        current = i;
        break;
      }
    }
    if (current < 0)
      return;
    orderNode.remove(current);
    orderNode.insert(Math.min(originalIndex, orderNode.size()), TextNode.valueOf(key));
  }
}

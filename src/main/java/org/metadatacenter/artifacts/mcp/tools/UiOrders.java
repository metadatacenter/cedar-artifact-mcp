package org.metadatacenter.artifacts.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Order manipulation on a rendered parent's {@code _ui.order}, for the {@code replace_*}
 * and {@code reorder_children} tools. The library's parent builders only ever append to
 * the order — there is no insert-at-position or reorder primitive — so position control
 * operates on the rendered JSON.
 */
final class UiOrders
{
  private UiOrders() {}

  /** Replace the whole display order. The caller guarantees {@code keys} is a permutation. */
  static void setOrder(ObjectNode rendered, java.util.List<String> keys)
  {
    JsonNode ui = rendered.path("_ui");
    if (!(ui instanceof ObjectNode))
      return;
    JsonNode order = ui.path("order");
    if (!(order instanceof ArrayNode orderNode))
      return;
    orderNode.removeAll();
    for (String key : keys)
      orderNode.add(key);
  }

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

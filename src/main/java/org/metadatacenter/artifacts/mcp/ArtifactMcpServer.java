package org.metadatacenter.artifacts.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.mcp.tools.CreateElementTool;
import org.metadatacenter.artifacts.mcp.tools.CreateFieldTool;
import org.metadatacenter.artifacts.mcp.tools.CreateTemplateTool;
import org.metadatacenter.artifacts.mcp.tools.ElementFromYamlTool;
import org.metadatacenter.artifacts.mcp.tools.ElementToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.FieldFromYamlTool;
import org.metadatacenter.artifacts.mcp.tools.FieldToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.TemplateFromYamlTool;
import org.metadatacenter.artifacts.mcp.tools.TemplateToYamlTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server that exposes the CEDAR artifact library's builders, readers, and renderers as
 * composable tools.
 *
 * <p>Tools live one-per-class under the {@code tools} package and are registered here via
 * {@link #main(String[])}. See {@code DESIGN.md} for the architectural principles and
 * {@code ROADMAP.md} for the planned tool inventory.
 */
public final class ArtifactMcpServer
{
  private static final String SERVER_NAME = "cedar-artifact-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private ArtifactMcpServer() {}

  public static void main(String[] args) throws InterruptedException
  {
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .toolCall(pingTool(), ArtifactMcpServer::pingHandler)
        .toolCall(CreateTemplateTool.tool(), CreateTemplateTool::handler)
        .toolCall(CreateElementTool.tool(), CreateElementTool::handler)
        .toolCall(CreateFieldTool.tool(), CreateFieldTool::handler)
        .toolCall(TemplateFromYamlTool.tool(), TemplateFromYamlTool::handler)
        .toolCall(ElementFromYamlTool.tool(), ElementFromYamlTool::handler)
        .toolCall(FieldFromYamlTool.tool(), FieldFromYamlTool::handler)
        .toolCall(TemplateToYamlTool.tool(), TemplateToYamlTool::handler)
        .toolCall(ElementToYamlTool.tool(), ElementToYamlTool::handler)
        .toolCall(FieldToYamlTool.tool(), FieldToYamlTool::handler)
        .build();

    // Stdio transport reads from System.in in a background thread. Keep the main thread
    // alive until the JVM is interrupted (typically when the parent process closes stdin).
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    Thread.currentThread().join();
  }

  // -----------------------------------------------------------------------
  // ping — diagnostic, no library interaction
  // -----------------------------------------------------------------------

  private static McpSchema.Tool pingTool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("message", Map.of("type", "string", "description", "Arbitrary string to echo back."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("message"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("ping")
        .title("ping")
        .description("Echoes the supplied message. Used to verify the MCP server is reachable.")
        .inputSchema(schema)
        .build();
  }

  private static McpSchema.CallToolResult pingHandler(
      io.modelcontextprotocol.server.McpSyncServerExchange exchange,
      McpSchema.CallToolRequest request)
  {
    Object raw = request.arguments() == null ? null : request.arguments().get("message");
    String message = raw == null ? "" : raw.toString();

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, "pong: " + message)))
        .isError(false)
        .build();
  }
}

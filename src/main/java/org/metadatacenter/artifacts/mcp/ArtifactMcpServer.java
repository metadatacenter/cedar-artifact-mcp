package org.metadatacenter.artifacts.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.metadatacenter.artifacts.mcp.tools.SetBranchConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.SetClassConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.SetLiteralDefaultValueTool;
import org.metadatacenter.artifacts.mcp.tools.AddElementTool;
import org.metadatacenter.artifacts.mcp.tools.AddFieldTool;
import org.metadatacenter.artifacts.mcp.tools.SetIriDefaultValueTool;
import org.metadatacenter.artifacts.mcp.tools.SetOntologyConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.SetOptionsTool;
import org.metadatacenter.artifacts.mcp.tools.SetValueSetConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.CreateElementTool;
import org.metadatacenter.artifacts.mcp.tools.CreateFieldTool;
import org.metadatacenter.artifacts.mcp.tools.CreateInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.CreateTemplateTool;
import org.metadatacenter.artifacts.mcp.tools.ElementToJsonTool;
import org.metadatacenter.artifacts.mcp.tools.ElementToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.FieldToJsonTool;
import org.metadatacenter.artifacts.mcp.tools.FieldToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.InstanceToJsonTool;
import org.metadatacenter.artifacts.mcp.tools.InstanceToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.RemoveChildTool;
import org.metadatacenter.artifacts.mcp.tools.ReplaceElementTool;
import org.metadatacenter.artifacts.mcp.tools.ReplaceFieldTool;
import org.metadatacenter.artifacts.mcp.tools.SetLiteralFieldValueTool;
import org.metadatacenter.artifacts.mcp.tools.SetIriFieldValueTool;
import org.metadatacenter.artifacts.mcp.tools.UnsetFieldValueTool;
import org.metadatacenter.artifacts.mcp.tools.TemplateToJsonTool;
import org.metadatacenter.artifacts.mcp.tools.TemplateToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateArtifactTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateElementTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateFieldTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateTemplateTool;

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
        .toolCall(TemplateToJsonTool.tool(), TemplateToJsonTool::handler)
        .toolCall(ElementToJsonTool.tool(), ElementToJsonTool::handler)
        .toolCall(FieldToJsonTool.tool(), FieldToJsonTool::handler)
        .toolCall(TemplateToYamlTool.tool(), TemplateToYamlTool::handler)
        .toolCall(ElementToYamlTool.tool(), ElementToYamlTool::handler)
        .toolCall(FieldToYamlTool.tool(), FieldToYamlTool::handler)
        .toolCall(AddFieldTool.tool(), AddFieldTool::handler)
        .toolCall(AddElementTool.tool(), AddElementTool::handler)
        .toolCall(RemoveChildTool.tool(), RemoveChildTool::handler)
        .toolCall(ReplaceFieldTool.tool(), ReplaceFieldTool::handler)
        .toolCall(ReplaceElementTool.tool(), ReplaceElementTool::handler)
        .toolCall(SetClassConstraintTool.tool(), SetClassConstraintTool::handler)
        .toolCall(SetOntologyConstraintTool.tool(), SetOntologyConstraintTool::handler)
        .toolCall(SetOptionsTool.tool(), SetOptionsTool::handler)
        .toolCall(SetBranchConstraintTool.tool(), SetBranchConstraintTool::handler)
        .toolCall(SetValueSetConstraintTool.tool(), SetValueSetConstraintTool::handler)
        .toolCall(SetLiteralDefaultValueTool.tool(), SetLiteralDefaultValueTool::handler)
        .toolCall(SetIriDefaultValueTool.tool(), SetIriDefaultValueTool::handler)
        .toolCall(CreateInstanceTool.tool(), CreateInstanceTool::handler)
        .toolCall(InstanceToJsonTool.tool(), InstanceToJsonTool::handler)
        .toolCall(InstanceToYamlTool.tool(), InstanceToYamlTool::handler)
        .toolCall(ValidateInstanceTool.tool(), ValidateInstanceTool::handler)
        .toolCall(ValidateTemplateTool.tool(), ValidateTemplateTool::handler)
        .toolCall(ValidateElementTool.tool(), ValidateElementTool::handler)
        .toolCall(ValidateFieldTool.tool(), ValidateFieldTool::handler)
        .toolCall(ValidateArtifactTool.tool(), ValidateArtifactTool::handler)
        .toolCall(SetLiteralFieldValueTool.tool(), SetLiteralFieldValueTool::handler)
        .toolCall(SetIriFieldValueTool.tool(), SetIriFieldValueTool::handler)
        .toolCall(UnsetFieldValueTool.tool(), UnsetFieldValueTool::handler)
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

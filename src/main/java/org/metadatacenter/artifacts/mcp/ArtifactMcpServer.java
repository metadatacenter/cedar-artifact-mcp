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
import org.metadatacenter.artifacts.mcp.tools.CreateElementInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.CreateTemplateInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.CreateTemplateTool;
import org.metadatacenter.artifacts.mcp.tools.InstanceArtifactToJsonTool;
import org.metadatacenter.artifacts.mcp.tools.InstanceArtifactToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.RemoveAnnotationTool;
import org.metadatacenter.artifacts.mcp.tools.RemoveChildTool;
import org.metadatacenter.artifacts.mcp.tools.RemoveConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.ReorderChildrenTool;
import org.metadatacenter.artifacts.mcp.tools.ReplaceElementTool;
import org.metadatacenter.artifacts.mcp.tools.ReplaceFieldTool;
import org.metadatacenter.artifacts.mcp.tools.SetLiteralAnnotationTool;
import org.metadatacenter.artifacts.mcp.tools.SetLiteralFieldValueTool;
import org.metadatacenter.artifacts.mcp.tools.SetElementInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.SetIriAnnotationTool;
import org.metadatacenter.artifacts.mcp.tools.SetIriFieldValueTool;
import org.metadatacenter.artifacts.mcp.tools.UnsetFieldValueTool;
import org.metadatacenter.artifacts.mcp.tools.SchemaArtifactToJsonTool;
import org.metadatacenter.artifacts.mcp.tools.SchemaArtifactToYamlTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateInstanceArtifactTool;
import org.metadatacenter.artifacts.mcp.tools.ValidateSchemaArtifactTool;

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
        .toolCall(SchemaArtifactToJsonTool.tool(), SchemaArtifactToJsonTool::handler)
        .toolCall(SchemaArtifactToYamlTool.tool(), SchemaArtifactToYamlTool::handler)
        .toolCall(AddFieldTool.tool(), AddFieldTool::handler)
        .toolCall(AddElementTool.tool(), AddElementTool::handler)
        .toolCall(RemoveChildTool.tool(), RemoveChildTool::handler)
        .toolCall(ReplaceFieldTool.tool(), ReplaceFieldTool::handler)
        .toolCall(ReplaceElementTool.tool(), ReplaceElementTool::handler)
        .toolCall(ReorderChildrenTool.tool(), ReorderChildrenTool::handler)
        .toolCall(SetClassConstraintTool.tool(), SetClassConstraintTool::handler)
        .toolCall(SetOntologyConstraintTool.tool(), SetOntologyConstraintTool::handler)
        .toolCall(SetOptionsTool.tool(), SetOptionsTool::handler)
        .toolCall(SetBranchConstraintTool.tool(), SetBranchConstraintTool::handler)
        .toolCall(SetValueSetConstraintTool.tool(), SetValueSetConstraintTool::handler)
        .toolCall(RemoveConstraintTool.tool(), RemoveConstraintTool::handler)
        .toolCall(SetLiteralDefaultValueTool.tool(), SetLiteralDefaultValueTool::handler)
        .toolCall(SetIriDefaultValueTool.tool(), SetIriDefaultValueTool::handler)
        .toolCall(CreateTemplateInstanceTool.tool(), CreateTemplateInstanceTool::handler)
        .toolCall(CreateElementInstanceTool.tool(), CreateElementInstanceTool::handler)
        .toolCall(InstanceArtifactToJsonTool.tool(), InstanceArtifactToJsonTool::handler)
        .toolCall(InstanceArtifactToYamlTool.tool(), InstanceArtifactToYamlTool::handler)
        .toolCall(ValidateInstanceArtifactTool.tool(), ValidateInstanceArtifactTool::handler)
        .toolCall(ValidateSchemaArtifactTool.tool(), ValidateSchemaArtifactTool::handler)
        .toolCall(SetLiteralFieldValueTool.tool(), SetLiteralFieldValueTool::handler)
        .toolCall(SetIriFieldValueTool.tool(), SetIriFieldValueTool::handler)
        .toolCall(SetElementInstanceTool.tool(), SetElementInstanceTool::handler)
        .toolCall(UnsetFieldValueTool.tool(), UnsetFieldValueTool::handler)
        .toolCall(SetLiteralAnnotationTool.tool(), SetLiteralAnnotationTool::handler)
        .toolCall(SetIriAnnotationTool.tool(), SetIriAnnotationTool::handler)
        .toolCall(RemoveAnnotationTool.tool(), RemoveAnnotationTool::handler)
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

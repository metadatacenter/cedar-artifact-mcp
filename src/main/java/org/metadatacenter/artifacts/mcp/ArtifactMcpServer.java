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
import org.metadatacenter.artifacts.mcp.tools.ReadArtifactFileTool;
import org.metadatacenter.artifacts.mcp.tools.WriteArtifactFileTool;
import org.metadatacenter.artifacts.mcp.tools.ConvertArtifactFileTool;
import org.metadatacenter.artifacts.mcp.tools.SetIriDefaultValueTool;
import org.metadatacenter.artifacts.mcp.tools.SetOntologyConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.SetOptionsTool;
import org.metadatacenter.artifacts.mcp.tools.SetValueSetConstraintTool;
import org.metadatacenter.artifacts.mcp.tools.CreateElementTool;
import org.metadatacenter.artifacts.mcp.tools.CreateFieldTool;
import org.metadatacenter.artifacts.mcp.tools.CreateElementInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.CreateTemplateInstanceTool;
import org.metadatacenter.artifacts.mcp.tools.CreateTemplateTool;
import org.metadatacenter.artifacts.mcp.tools.RemoveAnnotationTool;
import org.metadatacenter.artifacts.mcp.tools.SetAttributeValueTool;
import org.metadatacenter.artifacts.mcp.tools.UnsetAttributeValueTool;
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
import org.metadatacenter.artifacts.mcp.tools.RenderSchemaArtifactTool;
import org.metadatacenter.artifacts.mcp.tools.RenderInstanceArtifactTool;
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
  private static final String SERVER_VERSION = loadVersion();

  private ArtifactMcpServer() {}

  /**
   * Reads the build-stamped version from the filtered {@code <name>.version} resource. Falls back
   * to "unknown" when the resource is missing or was copied without filtering (e.g. an IDE run
   * that skips Maven resource filtering), so the server still starts.
   */
  private static String loadVersion()
  {
    try (java.io.InputStream in =
             ArtifactMcpServer.class.getResourceAsStream("/" + SERVER_NAME + ".version")) {
      if (in != null) {
        java.util.Properties props = new java.util.Properties();
        props.load(in);
        String version = props.getProperty("version");
        if (version != null && !version.isBlank() && !version.startsWith("${"))
          return version;
      }
    } catch (java.io.IOException ignored) {
      // fall through to the sentinel below
    }
    return "unknown";
  }

  public static void main(String[] args) throws InterruptedException
  {
    McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .toolCall(pingTool(), ArtifactMcpServer::pingHandler)
        .toolCall(CreateTemplateTool.tool(), CreateTemplateTool::handler)
        .toolCall(CreateElementTool.tool(), CreateElementTool::handler)
        .toolCall(CreateFieldTool.tool(), CreateFieldTool::handler)
        .toolCall(RenderSchemaArtifactTool.tool(), RenderSchemaArtifactTool::handler)
        .toolCall(RenderInstanceArtifactTool.tool(), RenderInstanceArtifactTool::handler)
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
        .toolCall(ValidateInstanceArtifactTool.tool(), ValidateInstanceArtifactTool::handler)
        .toolCall(ValidateSchemaArtifactTool.tool(), ValidateSchemaArtifactTool::handler)
        .toolCall(SetLiteralFieldValueTool.tool(), SetLiteralFieldValueTool::handler)
        .toolCall(SetIriFieldValueTool.tool(), SetIriFieldValueTool::handler)
        .toolCall(SetElementInstanceTool.tool(), SetElementInstanceTool::handler)
        .toolCall(UnsetFieldValueTool.tool(), UnsetFieldValueTool::handler)
        .toolCall(SetLiteralAnnotationTool.tool(), SetLiteralAnnotationTool::handler)
        .toolCall(SetIriAnnotationTool.tool(), SetIriAnnotationTool::handler)
        .toolCall(RemoveAnnotationTool.tool(), RemoveAnnotationTool::handler)
        .toolCall(SetAttributeValueTool.tool(), SetAttributeValueTool::handler)
        .toolCall(UnsetAttributeValueTool.tool(), UnsetAttributeValueTool::handler)
        .toolCall(ReadArtifactFileTool.tool(), ReadArtifactFileTool::handler)
        .toolCall(WriteArtifactFileTool.tool(), WriteArtifactFileTool::handler)
        .toolCall(ConvertArtifactFileTool.tool(), ConvertArtifactFileTool::handler)
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
        .description("Echoes the supplied message, with the server name and version appended. "
            + "Used to verify the MCP server is reachable and to report which build is running.")
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
        .content(List.of(new McpSchema.TextContent(null,
            "pong: " + message + " (" + SERVER_NAME + " " + SERVER_VERSION + ")")))
        .isError(false)
        .build();
  }
}

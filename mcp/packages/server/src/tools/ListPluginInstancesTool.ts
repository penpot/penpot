import { EmptyToolArgs, Tool } from "../Tool";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";

/** Lists the current user's connected Penpot browser tabs. */
export class ListPluginInstancesTool extends Tool<EmptyToolArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, EmptyToolArgs.schema);
    }

    public getToolName(): string {
        return "list_plugin_instances";
    }

    public getToolDescription(): string {
        return (
            "Lists Penpot browser tabs connected to this MCP server. " +
            "Use the returned pluginInstanceId with execute_code, export_shape, and import_image " +
            "to target a specific tab."
        );
    }

    protected async executeCore(_args: EmptyToolArgs): Promise<ToolResponse> {
        const userToken = this.getSessionContext()?.userToken;
        const instances = this.mcpServer.pluginBridge.listClientConnections(userToken);
        return new TextResponse(
            JSON.stringify(
                instances.map((instance) => ({
                    pluginInstanceId: instance.pluginInstanceId,
                    fileId: instance.fileId,
                    fileName: instance.fileName,
                    pageName: instance.pageName,
                    connected: instance.socket.readyState === 1,
                    frozen: instance.frozen,
                    lastHeartbeat: instance.lastHeartbeat,
                })),
                null,
                2
            )
        );
    }
}

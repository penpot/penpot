import { z } from "zod";
import { Tool } from "../Tool";
import { TextResponse, ToolResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { OpenFilePluginTask } from "../tasks/OpenFilePluginTask";

const uuidRegex = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/;

export class OpenFileArgs {
    static schema = {
        fileId: z
            .string()
            .regex(uuidRegex, "Must be a Penpot UUID string")
            .describe("ID of an existing Penpot team file."),
        pageId: z
            .string()
            .regex(uuidRegex)
            .optional()
            .describe("Optional UUID of a page in that file. If omitted, the default page opens."),
    };

    fileId!: string;

    pageId?: string;
}

export class OpenFileTool extends Tool<OpenFileArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, OpenFileArgs.schema);
    }

    public getToolName(): string {
        return "open_file";
    }

    public getToolDescription(): string {
        return (
            "Navigates in Penpot’s workspace UI to another file (and optionally a page). " +
            "Requires an active MCP plugin connection. The file must belong to the teams you have access to."
        );
    }

    protected async executeCore(args: OpenFileArgs): Promise<ToolResponse> {
        const task = new OpenFilePluginTask({ fileId: args.fileId, pageId: args.pageId });
        await this.mcpServer.pluginBridge.executePluginTask(task);
        return new TextResponse(JSON.stringify({ ok: true }, null, 2));
    }
}

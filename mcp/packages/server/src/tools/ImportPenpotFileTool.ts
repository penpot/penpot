import * as fs from "fs";
import * as path from "path";
import { z } from "zod";
import { Tool } from "../Tool";
import { TextResponse, ToolResponse } from "../ToolResponse";
import "reflect-metadata";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { ImportPenpotFilePluginTask } from "../tasks/ImportPenpotFilePluginTask";
import { FileUtils } from "../utils/FileUtils";

export class ImportPenpotFileArgs {
    static schema = {
        filePath: z.string().min(1).describe("Absolute path to a .penpot file to import."),
    };

    filePath!: string;
}

export class ImportPenpotFileTool extends Tool<ImportPenpotFileArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, ImportPenpotFileArgs.schema);
    }

    public getToolName(): string {
        return "import_penpot_file";
    }

    public getToolDescription(): string {
        return (
            "Imports a Penpot .penpot file from the local filesystem into the current team project. " +
            "Use when the MCP plugin is connected and you have opened a workspace file so a project exists. " +
            "Returns the UUIDs of the created file(s). Local filesystem access is required (not available in remote MCP mode)."
        );
    }

    protected async executeCore(args: ImportPenpotFileArgs): Promise<ToolResponse> {
        FileUtils.checkPathIsAbsolute(args.filePath);

        if (!fs.existsSync(args.filePath)) {
            throw new Error(`File not found: ${args.filePath}`);
        }

        const ext = path.extname(args.filePath).toLowerCase();
        if (ext !== ".penpot") {
            throw new Error(`Expected a .penpot file extension, got "${ext}".`);
        }

        const fileData = fs.readFileSync(args.filePath);
        const base64 = fileData.toString("base64");
        const filename = path.basename(args.filePath);

        const task = new ImportPenpotFilePluginTask({ filename, base64 });
        const result = await this.mcpServer.pluginBridge.executePluginTask(task);
        const data = result.data;
        const fileIds = data?.fileIds ?? [];
        return new TextResponse(JSON.stringify({ fileIds }, null, 2));
    }
}

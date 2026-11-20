import { z } from "zod";
import { type DocumentQuery, PenpotBackendClient } from "../PenpotBackendClient";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import { Tool } from "../Tool";
import { TextResponse, type ToolResponse } from "../ToolResponse";

/** Arguments for reading persisted document structure. */
export class GetDocumentArgs {
    static schema = {
        resource: z.enum(["teams", "projects", "files", "pages", "shapes"]),
        id: z
            .string()
            .uuid()
            .optional()
            .describe("Team ID for projects, project ID for files, file ID for pages or shapes."),
        pageId: z.string().uuid().optional().describe("Required for shapes; must belong to the selected file."),
        offset: z.number().int().min(0).max(1_000_000).optional(),
        limit: z.number().int().min(1).max(100).optional(),
        snapshot: z
            .string()
            .max(64)
            .optional()
            .describe("Use the returned snapshot for subsequent pages/shapes reads."),
    };

    resource!: DocumentQuery["resource"];
    id?: string;
    pageId?: string;
    offset?: number;
    limit?: number;
    snapshot?: string;
}

/** Read-only backend access that does not dispatch plugin tasks. */
export class GetDocumentTool extends Tool<GetDocumentArgs> {
    constructor(
        mcpServer: PenpotMcpServer,
        private readonly backend: PenpotBackendClient
    ) {
        super(mcpServer, GetDocumentArgs.schema);
    }

    public getToolName(): string {
        return "get_document";
    }

    public getToolDescription(): string {
        return (
            "Read saved Penpot document structure without a browser or plugin. " +
            "Start with teams, then select explicit IDs for projects, files, pages and shapes. " +
            "Returns summaries, not the Plugin API or unsaved editor state. No mutations or JavaScript execution. " +
            "Follow nextOffset; for pages/shapes pass the returned snapshot on continuation. " +
            "A changed snapshot requires restarting the read. Discovery lists may change between calls."
        );
    }

    protected async executeCore(args: GetDocumentArgs): Promise<ToolResponse> {
        const userToken = this.getSessionContext()?.userToken;
        if (!userToken?.trim()) {
            throw new Error("Authenticate with OAuth or include userToken in the MCP server URL to read documents.");
        }
        const result = await this.backend.getDocument(userToken, {
            resource: args.resource,
            id: args.id,
            "page-id": args.pageId,
            offset: args.offset,
            limit: args.limit,
            snapshot: args.snapshot,
        });
        return new TextResponse(JSON.stringify(result));
    }
}

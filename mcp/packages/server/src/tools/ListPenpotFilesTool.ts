import { z } from "zod";
import { Tool } from "../Tool";
import { TextResponse, ToolResponse } from "../ToolResponse";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { PenpotRestClient } from "../PenpotRestClient";

/**
 * Arguments class for ListPenpotFilesTool.
 */
export class ListPenpotFilesArgs {
    static schema = {
        projectId: z
            .string()
            .optional()
            .describe(
                "Optional Penpot project ID. When supplied, only files in that project are listed. " +
                    "When omitted, all projects accessible to the authenticated user are enumerated."
            ),
    };

    projectId?: string;
}

/**
 * Lists Penpot files via the Penpot REST/RPC API using a Personal Access Token.
 *
 * This tool runs entirely server-side and does not require the Penpot MCP plugin or a
 * connected browser session. It complements the plugin-based tools and only works when
 * `PENPOT_PAT` is configured.
 */
export class ListPenpotFilesTool extends Tool<ListPenpotFilesArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, ListPenpotFilesArgs.schema);
    }

    public getToolName(): string {
        return "list_penpot_files";
    }

    public getToolDescription(): string {
        return (
            "Lists Penpot files accessible to the authenticated user, optionally filtered by project ID. " +
            "Uses the Penpot REST API and a Personal Access Token; does not require the Penpot MCP plugin " +
            "or a browser session to be connected. If `projectId` is omitted, every project the user can " +
            "see is enumerated and its files are returned. This tool is unavailable when `PENPOT_PAT` is " +
            "not set in the server environment."
        );
    }

    protected async executeCore(args: ListPenpotFilesArgs): Promise<ToolResponse> {
        const client = PenpotRestClient.fromEnv();
        if (!client) {
            return new TextResponse(
                "Penpot PAT not configured on the server. Set the `PENPOT_PAT` environment variable " +
                    "(and optionally `PENPOT_BASE_URL` for self-hosted instances) and restart the MCP server."
            );
        }

        try {
            if (args.projectId) {
                const files = await client.getProjectFiles(args.projectId);
                return new TextResponse(
                    JSON.stringify(
                        {
                            baseUrl: client.baseUrl,
                            projectId: args.projectId,
                            count: files.length,
                            files: files.map((f) => ({
                                id: f.id,
                                name: f.name,
                                modifiedAt: f["modified-at"],
                            })),
                        },
                        null,
                        2
                    )
                );
            }

            const projects = await client.getAllProjects();
            const grouped: Record<string, unknown>[] = [];
            for (const project of projects) {
                const files = await client.getProjectFiles(project.id);
                grouped.push({
                    projectId: project.id,
                    projectName: project.name,
                    teamId: project["team-id"],
                    fileCount: files.length,
                    files: files.map((f) => ({
                        id: f.id,
                        name: f.name,
                        modifiedAt: f["modified-at"],
                    })),
                });
            }
            return new TextResponse(
                JSON.stringify(
                    {
                        baseUrl: client.baseUrl,
                        projectCount: projects.length,
                        totalFileCount: grouped.reduce((acc, g) => acc + (g.fileCount as number), 0),
                        projects: grouped,
                    },
                    null,
                    2
                )
            );
        } catch (err) {
            return new TextResponse(
                `Failed to list Penpot files via REST API: ${err instanceof Error ? err.message : String(err)}`
            );
        }
    }
}

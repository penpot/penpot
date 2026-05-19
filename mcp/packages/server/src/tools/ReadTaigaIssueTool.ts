import { z } from "zod";
import { Tool } from "../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import { PenpotMcpServer } from "../PenpotMcpServer";

/**
 * Arguments for the {@link ReadTaigaIssueTool}.
 */
export class ReadTaigaIssueArgs {
    static schema = {
        issueNumber: z
            .number()
            .int()
            .positive()
            .describe(
                "The Penpot issue number as it appears in Taiga URLs, " +
                    "e.g. 14177 for https://tree.taiga.io/project/penpot/issue/14177"
            ),
    };

    /**
     * The Penpot issue number as it appears in Taiga issue URLs
     * (e.g. 14177 for https://tree.taiga.io/project/penpot/issue/14177).
     */
    issueNumber!: number;
}

/**
 * Represents a file attachment on a Taiga issue.
 */
interface TaigaAttachment {
    filename: string;
    size: number;
    url: string;
}

/**
 * Represents a comment on a Taiga issue.
 */
interface TaigaComment {
    username: string;
    comment: string;
}

/**
 * The resolved issue data returned by the tool.
 */
interface TaigaIssueData {
    subject: string;
    description: string;
    status: string;
    attachments: TaigaAttachment[];
    comments: TaigaComment[];
}

/**
 * Tool for reading Penpot issues from the Taiga project tracker.
 *
 * Resolves a Penpot issue number to its internal Taiga ID and retrieves the issue's
 * subject, description, status, attachments, and comments via the Taiga REST API.
 */
export class ReadTaigaIssueTool extends Tool<ReadTaigaIssueArgs> {
    private static readonly TAIGA_API_BASE = "https://api.taiga.io/api/v1";

    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, ReadTaigaIssueArgs.schema);
    }

    public getToolName(): string {
        return "read_taiga_issue";
    }

    public getToolDescription(): string {
        return "Reads a Penpot issue from the Taiga project tracker, returning its subject, description, status, attachments, and comments.";
    }

    protected async executeCore(args: ReadTaigaIssueArgs): Promise<ToolResponse> {
        const { projectId, issueId } = await this.resolveIssue(args.issueNumber);
        const issueData = await this.fetchIssueData(projectId, issueId);
        return new TextResponse(JSON.stringify(issueData, null, 2));
    }

    /**
     * Resolves a Penpot issue number to the internal Taiga project and issue IDs.
     */
    private async resolveIssue(issueNumber: number): Promise<{ projectId: number; issueId: number }> {
        const url = `${ReadTaigaIssueTool.TAIGA_API_BASE}/resolver?project=penpot&issue=${issueNumber}`;
        const data = await this.fetchJson(url);
        return { projectId: data.project, issueId: data.issue };
    }

    /**
     * Fetches the full issue data including details, attachments, and comments.
     */
    private async fetchIssueData(projectId: number, issueId: number): Promise<TaigaIssueData> {
        // fetch issue details, attachments, and history in parallel
        const [details, attachments, comments] = await Promise.all([
            this.fetchIssueDetails(issueId),
            this.fetchAttachments(projectId, issueId),
            this.fetchComments(issueId),
        ]);

        return {
            subject: details.subject,
            description: details.description ?? "",
            status: details.status_extra_info?.name ?? "Unknown",
            attachments,
            comments,
        };
    }

    /**
     * Fetches the core issue details from the Taiga API.
     */
    private async fetchIssueDetails(issueId: number): Promise<any> {
        const url = `${ReadTaigaIssueTool.TAIGA_API_BASE}/issues/${issueId}`;
        return this.fetchJson(url);
    }

    /**
     * Fetches the attachments for an issue.
     */
    private async fetchAttachments(projectId: number, issueId: number): Promise<TaigaAttachment[]> {
        const url = `${ReadTaigaIssueTool.TAIGA_API_BASE}/issues/attachments?project=${projectId}&object_id=${issueId}`;
        const data: any[] = await this.fetchJson(url);
        return data.map((a) => ({
            filename: a.name,
            size: a.size,
            url: a.url,
        }));
    }

    /**
     * Fetches comments from the issue history.
     *
     * History entries that have a non-empty `comment` field are treated as comments.
     */
    private async fetchComments(issueId: number): Promise<TaigaComment[]> {
        const url = `${ReadTaigaIssueTool.TAIGA_API_BASE}/history/issue/${issueId}`;
        const history: any[] = await this.fetchJson(url);
        return history
            .filter((entry) => entry.comment && entry.comment.trim().length > 0)
            .map((entry) => ({
                username: entry.user?.username ?? "unknown",
                comment: entry.comment,
            }));
    }

    /**
     * Performs a GET request and returns the parsed JSON response.
     *
     * @throws Error if the HTTP response status is not OK
     */
    private async fetchJson(url: string): Promise<any> {
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`Taiga API request failed: ${response.status} ${response.statusText} (${url})`);
        }
        return response.json();
    }
}

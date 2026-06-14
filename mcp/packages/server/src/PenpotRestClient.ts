import { createLogger } from "./logger";

/**
 * Default base URL pointing to the hosted Penpot instance.
 *
 * Overridable via the `PENPOT_BASE_URL` environment variable for self-hosted deployments.
 */
const DEFAULT_BASE_URL = "https://design.penpot.app";

export interface PenpotRestClientOptions {
    baseUrl?: string;
    token?: string;
    /** Per-call timeout in milliseconds. */
    timeoutMs?: number;
}

export interface PenpotProfile {
    id: string;
    email: string;
    fullname?: string;
    [k: string]: unknown;
}

export interface PenpotTeam {
    id: string;
    name: string;
    [k: string]: unknown;
}

export interface PenpotProject {
    id: string;
    name: string;
    "team-id"?: string;
    [k: string]: unknown;
}

export interface PenpotFileSummary {
    id: string;
    name: string;
    "project-id"?: string;
    "modified-at"?: string;
    [k: string]: unknown;
}

/**
 * Thin wrapper around the Penpot RPC API using a Personal Access Token for auth.
 *
 * This client lets the MCP server perform a subset of design-file operations without
 * needing the browser plugin bridge. It complements (rather than replaces) the plugin
 * path: operations that require the browser-side Plugin API runtime — exporting,
 * selection tracking, CSS generation, etc. — must still go through the WebSocket
 * plugin bridge.
 */
export class PenpotRestClient {
    private readonly logger = createLogger("PenpotRestClient");
    public readonly baseUrl: string;
    private readonly token: string;
    private readonly timeoutMs: number;

    constructor(opts: PenpotRestClientOptions = {}) {
        const token = opts.token ?? process.env.PENPOT_PAT ?? "";
        if (!token) {
            throw new Error(
                "PenpotRestClient requires a Personal Access Token. Set PENPOT_PAT or pass `token` explicitly."
            );
        }
        this.token = token;
        this.baseUrl = (opts.baseUrl ?? process.env.PENPOT_BASE_URL ?? DEFAULT_BASE_URL).replace(/\/+$/, "");
        this.timeoutMs = opts.timeoutMs ?? 30_000;
    }

    /**
     * Whether the environment is configured for PAT-mode access.
     * Static so callers can decide between PAT and plugin paths without instantiating.
     */
    public static isConfigured(): boolean {
        return Boolean(process.env.PENPOT_PAT);
    }

    public static fromEnv(): PenpotRestClient | undefined {
        if (!PenpotRestClient.isConfigured()) {
            return undefined;
        }
        return new PenpotRestClient();
    }

    /**
     * Invokes an RPC method by name. Returns the parsed JSON response body.
     *
     * @param method - the RPC command name, e.g. `get-profile`
     * @param params - the JSON-serializable parameter object
     */
    public async call<T = unknown>(method: string, params: Record<string, unknown> = {}): Promise<T> {
        const url = `${this.baseUrl}/api/rpc/command/${encodeURIComponent(method)}`;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.timeoutMs);
        try {
            const res = await fetch(url, {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    Accept: "application/json",
                    Authorization: `Token ${this.token}`,
                },
                body: JSON.stringify(params),
                signal: controller.signal,
            });
            const text = await res.text();
            if (!res.ok) {
                this.logger.warn("Penpot RPC %s failed: status=%d body=%s", method, res.status, text.slice(0, 500));
                throw new Error(
                    `Penpot RPC ${method} failed: HTTP ${res.status} ${res.statusText} — ${text.slice(0, 500)}`
                );
            }
            if (!text) {
                return undefined as T;
            }
            try {
                return JSON.parse(text) as T;
            } catch (parseErr) {
                throw new Error(`Penpot RPC ${method} returned non-JSON body: ${text.slice(0, 500)}`);
            }
        } finally {
            clearTimeout(timer);
        }
    }

    public getProfile(): Promise<PenpotProfile> {
        return this.call<PenpotProfile>("get-profile");
    }

    public getTeams(): Promise<PenpotTeam[]> {
        return this.call<PenpotTeam[]>("get-teams");
    }

    public getAllProjects(): Promise<PenpotProject[]> {
        return this.call<PenpotProject[]>("get-all-projects");
    }

    public getProjectFiles(projectId: string): Promise<PenpotFileSummary[]> {
        return this.call<PenpotFileSummary[]>("get-project-files", { "project-id": projectId });
    }

    public getFile(fileId: string): Promise<Record<string, unknown>> {
        return this.call<Record<string, unknown>>("get-file", { id: fileId });
    }
}

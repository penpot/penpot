import { createLogger } from "./logger";

/** A plain-text MCP feedback report. */
export interface Feedback {
    subject: string;
    feedback: string;
}

/** Backend operations available to MCP tools. */
export interface PenpotBackend {
    submitFeedback(userToken: string, feedback: Feedback): Promise<void>;
}

/** Client for the configured Penpot instance's backend services. */
export class PenpotBackendClient implements PenpotBackend {
    private readonly logger = createLogger("PenpotBackendClient");
    private readonly baseUri: URL;

    constructor(baseUri: string) {
        try {
            this.baseUri = new URL(baseUri);
        } catch {
            throw new Error("PENPOT_MCP_BACKEND_URI must be an absolute HTTP(S) instance URI.");
        }
        if (
            !["http:", "https:"].includes(this.baseUri.protocol) ||
            this.baseUri.username ||
            this.baseUri.password ||
            this.baseUri.search ||
            this.baseUri.hash
        ) {
            throw new Error(
                "PENPOT_MCP_BACKEND_URI must be an HTTP(S) instance URI without credentials, query or fragment."
            );
        }
        if (!this.baseUri.pathname.endsWith("/")) this.baseUri.pathname += "/";
    }

    public async submitFeedback(userToken: string, feedback: Feedback): Promise<void> {
        await this.callRpc("send-mcp-feedback", userToken, feedback);
    }

    /** Calls a backend RPC method whose result body is not needed. */
    private async callRpc(methodName: string, userToken: string, params: object): Promise<void> {
        let response: Response;
        try {
            response = await fetch(new URL(`api/main/methods/${encodeURIComponent(methodName)}`, this.baseUri), {
                method: "POST",
                headers: {
                    Authorization: `Token ${userToken}`,
                    "X-Client": "penpot-mcp",
                    "Content-Type": "application/json",
                    Accept: "application/json",
                },
                body: JSON.stringify(params),
                redirect: "manual",
                signal: AbortSignal.timeout(10_000),
            });
            // discard backend diagnostics, which may contain private data
            await response.body?.cancel();
        } catch (error) {
            this.logger.error(error, "Penpot RPC request failed");
            throw new Error("The backend operation could not be confirmed. Do not retry automatically.");
        }

        if (response.ok) return;

        switch (response.status) {
            case 401:
                throw new Error(
                    "The backend did not accept the MCP user token. It may be outdated, " +
                        "or the Penpot instance may not have access tokens enabled."
                );
            case 403:
                throw new Error("The MCP user token is not authorized for this backend operation.");
            case 400:
                throw new Error("The backend rejected the request parameters or the operation is disabled.");
            case 429:
                throw new Error("The backend request limit has been reached. Do not retry automatically.");
            default:
                throw new Error(
                    `The backend operation could not be confirmed (HTTP ${response.status}). Do not retry automatically.`
                );
        }
    }
}

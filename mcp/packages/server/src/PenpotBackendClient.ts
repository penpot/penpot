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
        let response: Response;
        try {
            response = await fetch(new URL("api/mcp/feedback", this.baseUri), {
                method: "POST",
                headers: {
                    Authorization: `Bearer ${userToken}`,
                    "Content-Type": "application/json",
                    Accept: "application/json",
                },
                body: JSON.stringify(feedback),
                redirect: "manual",
                signal: AbortSignal.timeout(10_000),
            });
            // discard backend diagnostics, which may contain private data
            await response.body?.cancel();
        } catch (error) {
            this.logger.error(error, "Feedback submission failed");
            throw new Error("Feedback submission could not be confirmed. Do not retry automatically.");
        }

        switch (response.status) {
            case 202:
                return;
            case 401:
                throw new Error("The MCP user token is invalid or expired. Update userToken in the MCP server URL.");
            case 400:
                throw new Error("The backend rejected the feedback subject or text.");
            case 429:
                throw new Error("The feedback submission limit has been reached. Do not retry automatically.");
            case 503:
                throw new Error("Feedback submission is unavailable on this Penpot instance.");
            default:
                throw new Error(
                    `Feedback submission could not be confirmed (HTTP ${response.status}). Do not retry automatically.`
                );
        }
    }
}

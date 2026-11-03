import type { Request, Response } from "express";

/**
 * Result of processing credentials from an MCP HTTP request.
 */
export class McpAuthenticationResult {
    /**
     * @param userToken the extracted user token, if present
     * @param isRejected whether the request was rejected and its HTTP error response sent
     */
    constructor(
        public readonly userToken: string | undefined,
        public readonly isRejected: boolean = false
    ) {}
}

/**
 * HTTP credential extraction and optional OAuth discovery for MCP clients.
 *
 * Tokens retain their existing role of matching requests to Penpot connections.
 */
export class McpAuthentication {
    public readonly isOAuthEnabled: boolean;
    private readonly resourceMetadataUri?: string;

    /**
     * Creates a new MCP authentication handler.
     *
     * @param authUri the authentication URI from PENPOT_MCP_AUTH_URI, or undefined/empty to disable OAuth
     * @param multiUser whether the server is running in multi-user mode
     */
    constructor(authUri: string | undefined, multiUser: boolean) {
        if (!authUri?.trim()) {
            this.isOAuthEnabled = false;
        } else {
            if (!multiUser) {
                throw new Error("PENPOT_MCP_AUTH_URI (which enables OAuth) requires multi-user mode");
            }

            // validate the authentication URI
            let uri: URL;
            try {
                uri = new URL(authUri);
            } catch {
                throw new Error("PENPOT_MCP_AUTH_URI must be an absolute HTTP(S) URL");
            }
            const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(uri.hostname);
            if (
                (uri.protocol !== "https:" && !(uri.protocol === "http:" && loopback)) ||
                uri.username ||
                uri.password ||
                uri.search ||
                uri.hash
            ) {
                throw new Error(
                    "PENPOT_MCP_AUTH_URI must use HTTPS (HTTP is allowed on loopback) without credentials, query, or fragment"
                );
            }

            this.resourceMetadataUri = uri.href.replace(/\/$/, "") + "/resource";
            this.isOAuthEnabled = true;
        }
    }

    /**
     * Extracts credentials.
     * If credentials are absent while OAuth is enabled, sends an authentication error.
     */
    public authenticate(req: Request, res: Response): McpAuthenticationResult {
        let userToken: string | undefined;

        // check for a Bearer token in the Authorization header
        const header = req.headers.authorization;
        if (header !== undefined) {
            const headerCount = req.rawHeaders.filter(
                (_, i) => i % 2 === 0 && req.rawHeaders[i].toLowerCase() === "authorization"
            ).length;
            const match = /^Bearer +([A-Za-z0-9\-._~+/]+=*)$/i.exec(header);
            if (headerCount !== 1 || !match) {
                this.rejectOAuth(res, "invalid_token");
                return new McpAuthenticationResult(undefined, true);
            }
            userToken = match[1];
        }
        // check for a userToken query parameter
        else if (req.query.userToken !== undefined) {
            const value = req.query.userToken;
            if (typeof value !== "string" || !/^[A-Za-z0-9\-._~+/]+=*$/.test(value)) {
                this.rejectOAuth(res, "invalid_token");
                return new McpAuthenticationResult(undefined, true);
            }
            userToken = value;
        }

        // NOTE: The MCP server does not validate userToken against the backend.
        // It uses the token to match MCP requests to a connection established
        // from the Penpot webapp. Without that matching connection, requests
        // cannot access or modify designs. The user must be logged in to Penpot
        // to establish that connection; Penpot login and file permissions thus
        // provide the authentication and access control boundary.

        if (this.isOAuthEnabled && !userToken) {
            this.rejectOAuth(res);
            return new McpAuthenticationResult(undefined, true);
        } else {
            return new McpAuthenticationResult(userToken);
        }
    }

    private rejectOAuth(res: Response, error?: "invalid_token"): void {
        let challenge = "Bearer";
        if (this.resourceMetadataUri) {
            challenge += ` resource_metadata="${this.resourceMetadataUri}", scope="mcp"`;
        }
        if (error) challenge += `${this.resourceMetadataUri ? "," : ""} error="${error}"`;
        res.setHeader("WWW-Authenticate", challenge);
        res.setHeader("Cache-Control", "no-store");
        res.status(401).json({ error: error ?? "unauthorized" });
    }
}

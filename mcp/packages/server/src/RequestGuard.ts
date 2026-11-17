import type { IncomingHttpHeaders } from "node:http";
import type { NextFunction, Request, Response } from "express";
import { localhostAllowedHostnames, validateHostHeader, validateOriginHeader } from "@modelcontextprotocol/server";
import { createLogger } from "./logger";

/**
 * Outcome of checking the headers of one request against a {@link RequestGuard}.
 */
export class RequestGuardResult {
    /**
     * @param isAllowed whether the request may proceed
     * @param reason why the request was rejected; empty when it is allowed
     */
    constructor(
        public readonly isAllowed: boolean,
        public readonly reason: string = ""
    ) {}
}

/**
 * Allowlist check of the `Host` and `Origin` headers of requests reaching the MCP server.
 *
 * Any web page the user opens can send requests to servers on the user's machine. The `Host`
 * check stops DNS rebinding, where a page on a foreign domain whose name resolves to the
 * loopback address reaches the server as a same-origin request. The `Origin` check stops
 * pages on foreign sites from sending requests or opening the plugin WebSocket directly.
 * Both headers are compared by hostname only, ignoring the port. A request without an
 * `Origin` header comes from a client other than a browser and passes the `Origin` check.
 *
 * The allowed hostnames come from `PENPOT_MCP_ALLOWED_HOSTS`, where `*` allows any hostname.
 * When it is unset, local mode allows only loopback hostnames and remote mode allows any
 * hostname, since a remote server is reached under names it cannot know and relies on user
 * tokens instead.
 */
export class RequestGuard {
    private readonly logger = createLogger("RequestGuard");

    /**
     * @param allowedHostnames the hostnames allowed in `Host` and `Origin`, or null to allow any
     */
    private constructor(private readonly allowedHostnames: string[] | null) {}

    /**
     * Creates the guard configured by `PENPOT_MCP_ALLOWED_HOSTS`.
     *
     * The variable holds a comma-separated list of hostnames, without ports; IPv6 addresses
     * go in brackets (e.g. `[::1]`). The entry `*` allows any hostname, which turns the check off.
     *
     * @param env the environment variables
     * @param isRemoteMode whether the server runs in remote mode
     */
    public static fromEnvironment(env: Record<string, string | undefined>, isRemoteMode: boolean): RequestGuard {
        const configured = (env.PENPOT_MCP_ALLOWED_HOSTS ?? "")
            .split(",")
            .map((hostname) => hostname.trim().toLowerCase())
            .filter((hostname) => hostname.length > 0);
        if (configured.includes("*")) {
            return RequestGuard.allowingAny();
        }
        if (configured.length > 0) {
            return new RequestGuard(configured);
        }
        return isRemoteMode ? RequestGuard.allowingAny() : RequestGuard.allowing(localhostAllowedHostnames());
    }

    /**
     * Creates a guard that allows only the given hostnames.
     */
    public static allowing(hostnames: string[]): RequestGuard {
        return new RequestGuard([...hostnames]);
    }

    /**
     * Creates a guard that allows every request.
     */
    public static allowingAny(): RequestGuard {
        return new RequestGuard(null);
    }

    /**
     * Describes the allowed hostnames, for logging.
     */
    public describe(): string {
        return this.allowedHostnames === null ? "<any>" : this.allowedHostnames.join(", ");
    }

    /**
     * Checks the `Host` and `Origin` headers of a request.
     */
    public check(headers: IncomingHttpHeaders): RequestGuardResult {
        if (this.allowedHostnames === null) {
            return new RequestGuardResult(true);
        }
        const host = validateHostHeader(headers.host, this.allowedHostnames);
        if (!host.ok) {
            return new RequestGuardResult(false, host.message);
        }
        const origin = validateOriginHeader(headers.origin, this.allowedHostnames);
        if (!origin.ok) {
            return new RequestGuardResult(false, origin.message);
        }
        return new RequestGuardResult(true);
    }

    /**
     * Express middleware that answers a rejected request with HTTP 403.
     */
    public readonly httpMiddleware = (req: Request, res: Response, next: NextFunction): void => {
        const result = this.check(req.headers);
        if (result.isAllowed) {
            next();
            return;
        }
        this.logger.warn("Rejected HTTP request to %s: %s", req.path, result.reason);
        res.status(403).json({
            jsonrpc: "2.0",
            error: { code: -32000, message: `${result.reason}; see PENPOT_MCP_ALLOWED_HOSTS` },
            id: null,
        });
    };
}

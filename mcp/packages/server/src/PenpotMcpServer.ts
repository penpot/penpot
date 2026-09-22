import { createMcpHandler, McpServer, type McpHttpHandler } from "@modelcontextprotocol/server";
import { toNodeHandler } from "@modelcontextprotocol/node";
import type { Server as HttpServer } from "node:http";
import type { Express } from "express";
import { z } from "zod";
import { AsyncLocalStorage } from "async_hooks";
import { SSEServerTransport } from "@modelcontextprotocol/server-legacy/sse";
import { ExecuteCodeTool } from "./tools/ExecuteCodeTool";
import { PluginBridge } from "./PluginBridge";
import { RedisBridge } from "./RedisBridge";
import { ConfigurationLoader } from "./ConfigurationLoader";
import { createLogger } from "./logger";
import { Tool } from "./Tool";
import { HighLevelOverviewTool } from "./tools/HighLevelOverviewTool";
import { PenpotApiInfoTool } from "./tools/PenpotApiInfoTool";
import { ExportShapeTool } from "./tools/ExportShapeTool";
import { ImportImageTool } from "./tools/ImportImageTool";
import { CljsReplTool } from "./tools/CljsReplTool";
import { ImportPenpotFileTool } from "./tools/ImportPenpotFileTool";
import { CljsCompilerOutputTool } from "./tools/CljsCompilerOutputTool";
import { CljCheckParentheses } from "./tools/CljCheckParentheses";
import { ReadTaigaIssueTool } from "./tools/ReadTaigaIssueTool";
import { NreplClient } from "./NreplClient";
import { ReplServer } from "./ReplServer";
import { ApiDocs } from "./ApiDocs";

/**
 * Session context for request-scoped data.
 */
export interface SessionContext {
    userToken?: string;
}

/**
 * Holds information about a registered tool, including its instance, name, and configuration.
 */
class ToolInfo {
    constructor(
        public readonly instance: Tool<any>,
        public readonly name: string,
        public readonly config: { description: string; inputSchema: z.ZodObject<z.ZodRawShape> }
    ) {}
}

/**
 * Indicates whether developer tools may be registered for the current server mode.
 */
export function shouldRegisterDeveloperTools(isDevEnv: boolean, isMultiUserMode: boolean): boolean {
    return isDevEnv && !isMultiUserMode;
}

/**
 * Indicates whether the REPL server may be started for the current server mode.
 *
 * The REPL server never starts in multi-user mode, even when explicitly
 * enabled, mirroring the developer tools policy.
 */
export function shouldStartReplServer(isReplEnabled: boolean, isMultiUserMode: boolean): boolean {
    return isReplEnabled && !isMultiUserMode;
}

export class PenpotMcpServer {
    /**
     * Determines whether the server is running in a Penpot development
     * environment, based on the given environment variables.
     *
     * Returns ``true`` only when ``PENPOT_MCP_DEVENV`` is ``"true"``.
     */
    public static isDevEnvEnabled(env: Record<string, string | undefined>): boolean {
        return env.PENPOT_MCP_DEVENV === "true";
    }

    /**
     * Determines whether the REPL server should be enabled.
     *
     * If ``PENPOT_MCP_REPL_ENABLE`` is set, its value controls the result
     * (``"true"`` enables, any other value disables). When the variable is
     * not set, the result falls back to {@link isDevEnvEnabled}.
     */
    public static isReplEnabled(env: Record<string, string | undefined>): boolean {
        if (env.PENPOT_MCP_REPL_ENABLE !== undefined) {
            return env.PENPOT_MCP_REPL_ENABLE === "true";
        }
        return PenpotMcpServer.isDevEnvEnabled(env);
    }

    /**
     * Returns a short, non-reversible fingerprint of a user token, suitable for
     * correlating log lines without exposing the full credential.
     *
     * Penpot tokens are JWEs in compact serialization (RFC 7516 §7.1) with five
     * dot-separated segments; we use the first 8 chars of the wrapped CEK
     * (segment 1) as a stable per-token identifier. For malformed tokens (e.g.
     * test stubs that aren't real JWEs), we fall back to the first 8 chars of
     * the raw token.
     *
     * @param token - the token to fingerprint, or `undefined`
     * @returns a short fingerprint, or `<none>` if no token was given
     */
    private static tokenFingerprint(token: string | undefined): string {
        if (!token) {
            return "<none>";
        }
        const segments = token.split(".");
        const source = segments.length === 5 ? segments[1] : token;
        return source.slice(0, 8);
    }

    private readonly logger = createLogger("PenpotMcpServer");
    private readonly tools: ToolInfo[];
    public readonly configLoader: ConfigurationLoader;
    private app!: Express;
    private httpServer?: HttpServer;
    private readonly mcpHandler: McpHttpHandler;
    public readonly pluginBridge: PluginBridge;
    private readonly replServer: ReplServer | null;
    private apiDocs: ApiDocs;
    private readonly penpotHighLevelOverview: string;
    private readonly connectionInstructions: string;

    /**
     * Carries the user token through each request and its asynchronous tool execution.
     */
    private readonly sessionContext = new AsyncLocalStorage<SessionContext>();

    private readonly sseTransports = new Map<string, { transport: SSEServerTransport; userToken?: string }>();

    public readonly host: string;
    public readonly port: number;
    public readonly webSocketPort: number;
    public readonly replHost: string;
    public readonly replPort: number;

    /**
     * Optional Redis bridge for multi-instance task routing; present only when running
     * in multi-user mode with a configured Redis URI.
     */
    private readonly redisBridge?: RedisBridge;

    /**
     * Tenant identifier, read from the `PENPOT_TENANT` environment variable.
     *
     * Used to qualify Redis channel names so that multiple environments sharing a
     * Redis instance do not interfere with each other. Defaults to `"default"`,
     * matching the backend default.
     */
    private readonly tenant: string;

    constructor(private isMultiUser: boolean = false) {
        // read port configuration from environment variables
        this.host = process.env.PENPOT_MCP_SERVER_HOST ?? "localhost";
        this.port = parseInt(process.env.PENPOT_MCP_SERVER_PORT ?? "4401", 10);
        this.webSocketPort = parseInt(process.env.PENPOT_MCP_WEBSOCKET_PORT ?? "4402", 10);
        this.replHost = process.env.PENPOT_MCP_REPL_HOST ?? "localhost";
        this.replPort = parseInt(process.env.PENPOT_MCP_REPL_PORT ?? "4403", 10);
        this.tenant = process.env.PENPOT_TENANT ?? "default";
        const toolTimeoutSecs = parseInt(process.env.PENPOT_MCP_TOOL_TIMEOUT_S ?? "120", 10);

        this.configLoader = new ConfigurationLoader(process.cwd());
        this.apiDocs = new ApiDocs();

        // prepare instructions
        let instructions = this.configLoader.getInitialInstructions();
        instructions = instructions.replace("$api_types", this.apiDocs.getTypeNames().join(", "));
        this.penpotHighLevelOverview = instructions;
        this.connectionInstructions = this.configLoader.getBaseInstructions();

        this.tools = this.initTools();
        this.mcpHandler = createMcpHandler(() => this.createMcpServer());

        // Enable multi-instance task routing when running in multi-user mode with a
        // configured Redis URI. Without it, the server operates in single-instance mode,
        // requiring the plugin and the MCP client to connect to the same instance.
        const redisUri = process.env.PENPOT_MCP_REDIS_URI;
        if (this.isMultiUser && redisUri) {
            this.redisBridge = new RedisBridge(redisUri, this.tenant);
        }

        this.pluginBridge = new PluginBridge(this, this.webSocketPort, toolTimeoutSecs, this.redisBridge);

        if (shouldStartReplServer(PenpotMcpServer.isReplEnabled(process.env), this.isMultiUserMode())) {
            this.replServer = new ReplServer(this.pluginBridge, this.replPort, this.replHost);
        } else {
            this.replServer = null;
        }
    }

    /**
     * Indicates whether the server is running in multi-user mode,
     * where user tokens are required for authentication.
     */
    public isMultiUserMode(): boolean {
        return this.isMultiUser;
    }

    /**
     * Indicates whether the server is running in remote mode.
     *
     * In remote mode, the server is not assumed to be accessed only by a local user on the same machine,
     * with corresponding limitations being enforced.
     * Remote mode can be explicitly enabled by setting the environment variable PENPOT_MCP_REMOTE_MODE
     * to "true". Enabling multi-user mode forces remote mode, regardless of the value of the environment
     * variable.
     */
    public isRemoteMode(): boolean {
        const isRemoteModeRequested: boolean = process.env.PENPOT_MCP_REMOTE_MODE === "true";
        return this.isMultiUserMode() || isRemoteModeRequested;
    }

    /**
     * Indicates whether file system access is enabled for MCP tools.
     * Access is enabled only in local mode, where the file system is assumed
     * to belong to the user running the server locally.
     */
    public isFileSystemAccessEnabled(): boolean {
        return !this.isRemoteMode();
    }

    /**
     * Indicates whether the server is running in a Penpot development environment.
     *
     * When enabled (by setting the environment variable PENPOT_MCP_DEVENV to "true"),
     * additional developer tools such as ClojureScript expression evaluation are exposed.
     */
    public isDevEnv(): boolean {
        return PenpotMcpServer.isDevEnvEnabled(process.env);
    }

    /**
     * Indicates whether the REPL server was created.
     *
     * The REPL server is created when {@link isReplEnabled} returns true and
     * the server is not running in multi-user mode, which means either
     * ``PENPOT_MCP_REPL_ENABLE=true`` or, when that variable is unset,
     * ``PENPOT_MCP_DEVENV=true``, in single-user mode.
     */
    public hasReplServer(): boolean {
        return this.replServer !== null;
    }

    /**
     * Retrieves the high-level overview instructions explaining core Penpot usage.
     */
    public getHighLevelOverviewInstructions(): string {
        return this.penpotHighLevelOverview;
    }

    /**
     * Retrieves the current session context.
     *
     * @returns The session context for the current request, or undefined if not in a request context
     */
    public getSessionContext(): SessionContext | undefined {
        return this.sessionContext.getStore();
    }

    private initTools(): ToolInfo[] {
        const toolInstances: Tool<any>[] = [
            new ExecuteCodeTool(this),
            new HighLevelOverviewTool(this),
            new PenpotApiInfoTool(this, this.apiDocs),
            new ExportShapeTool(this),
        ];
        if (this.isFileSystemAccessEnabled()) {
            toolInstances.push(new ImportImageTool(this));
        }
        if (shouldRegisterDeveloperTools(this.isDevEnv(), this.isMultiUserMode())) {
            const nreplClient = new NreplClient();
            toolInstances.push(new CljsReplTool(this, nreplClient));
            toolInstances.push(new ImportPenpotFileTool(this, nreplClient));
            toolInstances.push(new CljsCompilerOutputTool(this, nreplClient));
            toolInstances.push(new CljCheckParentheses(this));
            toolInstances.push(new ReadTaigaIssueTool(this));
        }

        return toolInstances.map((instance) => {
            this.logger.info(`Registering tool: ${instance.getToolName()}`);
            return new ToolInfo(instance, instance.getToolName(), {
                description: instance.getToolDescription(),
                inputSchema: z.object(instance.getInputSchema()),
            });
        });
    }

    /**
     * Creates a fresh {@link McpServer} instance with all tools registered.
     */
    private createMcpServer(): McpServer {
        const server = new McpServer(
            { name: "penpot", version: "1.0.0" },
            { instructions: this.connectionInstructions }
        );

        for (const tool of this.tools) {
            server.registerTool(tool.name, tool.config, async (args) => tool.instance.execute(args));
        }

        return server;
    }

    private setupHttpEndpoints(): void {
        const handleMcpRequest = toNodeHandler(this.mcpHandler);
        this.app.all("/mcp", async (req, res) => {
            const userToken = req.query.userToken as string | undefined;
            this.logger.info(
                `Received MCP request: method=${req.body?.method ?? "<none>"}; userTokenFp=${PenpotMcpServer.tokenFingerprint(userToken)}`
            );
            await this.sessionContext.run({ userToken }, () => handleMcpRequest(req, res, req.body));
        });

        /**
         * Legacy SSE connection endpoint.
         */
        this.app.get("/sse", async (req: any, res: any) => {
            const userToken = req.query.userToken as string | undefined;

            await this.sessionContext.run({ userToken }, async () => {
                const transport = new SSEServerTransport("/messages", res);
                this.sseTransports.set(transport.sessionId, { transport, userToken });

                const server = this.createMcpServer();
                res.on("close", () => {
                    this.sseTransports.delete(transport.sessionId);
                    void server.close();
                });
                await server.connect(transport);
            });
        });

        /**
         * SSE message POST endpoint (using previously established session)
         */
        this.app.post("/messages", async (req: any, res: any) => {
            const sessionId = req.query.sessionId as string;
            const session = this.sseTransports.get(sessionId);

            if (session) {
                await this.sessionContext.run({ userToken: session.userToken }, async () => {
                    await session.transport.handlePostMessage(req, res, req.body);
                });
            } else {
                res.status(400).send("No transport found for sessionId");
            }
        });
    }

    async start(): Promise<void> {
        const { default: express } = await import("express");
        this.app = express();
        this.app.use(express.json());

        this.setupHttpEndpoints();

        return new Promise((resolve, reject) => {
            this.httpServer = this.app.listen(this.port, this.host, async () => {
                this.logger.info(`Multi-user mode: ${this.isMultiUserMode()}`);
                this.logger.info(
                    `Multi-instance mode with Redis-backed transport: ${this.redisBridge ? "true" : "false"}`
                );
                this.logger.info(`Remote mode: ${this.isRemoteMode()}`);
                this.logger.info(`DevEnv mode: ${this.isDevEnv()}`);
                this.logger.info(`Modern Streamable HTTP endpoint: http://${this.host}:${this.port}/mcp`);
                this.logger.info(`Legacy SSE endpoint: http://${this.host}:${this.port}/sse`);
                this.logger.info(`WebSocket server URL: ws://${this.host}:${this.webSocketPort}`);

                // start the REPL server when enabled
                if (this.replServer) {
                    await this.replServer.start();
                } else if (this.isMultiUserMode()) {
                    this.logger.info("REPL server disabled in multi-user mode (never started with --multi-user)");
                } else {
                    this.logger.info(
                        "REPL server disabled (set PENPOT_MCP_REPL_ENABLE=true or PENPOT_MCP_DEVENV=true to enable)"
                    );
                }

                resolve();
            });
            this.httpServer.once("error", reject);
        });
    }

    /**
     * Stops the MCP server and associated services.
     *
     * Gracefully shuts down the REPL server and other components.
     */
    public async stop(): Promise<void> {
        this.logger.info("Stopping Penpot MCP Server...");
        const httpClosed = this.httpServer
            ? new Promise<void>((resolve, reject) => {
                  this.httpServer!.close((error) => (error ? reject(error) : resolve()));
              })
            : Promise.resolve();
        await this.mcpHandler.close();
        await Promise.all(Array.from(this.sseTransports.values(), ({ transport }) => transport.close()));
        this.sseTransports.clear();
        await httpClosed;
        await this.pluginBridge.close();
        await this.redisBridge?.close();
        if (this.replServer) {
            await this.replServer.stop();
        }
        this.logger.info("Penpot MCP Server stopped");
    }
}

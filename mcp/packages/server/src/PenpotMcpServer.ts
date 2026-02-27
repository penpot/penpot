import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { AsyncLocalStorage } from "async_hooks";
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { ExecuteCodeTool } from "./tools/ExecuteCodeTool";
import { PluginBridge } from "./PluginBridge";
import { ConfigurationLoader } from "./ConfigurationLoader";
import { createLogger } from "./logger";
import { Tool } from "./Tool";
import { HighLevelOverviewTool } from "./tools/HighLevelOverviewTool";
import { PenpotApiInfoTool } from "./tools/PenpotApiInfoTool";
import { ExportShapeTool } from "./tools/ExportShapeTool";
import { ImportImageTool } from "./tools/ImportImageTool";
import { ReplServer } from "./ReplServer";
import { ApiDocs } from "./ApiDocs";

/**
 * Session context for request-scoped data.
 */
export interface SessionContext {
    userToken?: string;
}

/**
 * Represents an active Streamable HTTP session, grouping the transport, MCP server, and session metadata.
 */
class StreamableSession {
    constructor(
        public readonly transport: StreamableHTTPServerTransport,
        public readonly userToken: string | undefined,
        public lastActiveTime: number
    ) {}
}

export class PenpotMcpServer {
    /**
     * Timeout, in minutes, for idle Streamable HTTP sessions before they are automatically closed and removed.
     */
    private static readonly SESSION_TIMEOUT_MINUTES = 60;

    private readonly logger = createLogger("PenpotMcpServer");
    private readonly tools: Map<string, Tool<any>>;
    public readonly configLoader: ConfigurationLoader;
    private app: any;
    public readonly pluginBridge: PluginBridge;
    private readonly replServer: ReplServer;
    private apiDocs: ApiDocs;

    /**
     * Manages session-specific context, particularly user tokens for each request.
     */
    private readonly sessionContext = new AsyncLocalStorage<SessionContext>();

    private readonly streamableTransports: Record<string, StreamableSession> = {};
    private readonly sseTransports: Record<string, { transport: SSEServerTransport; userToken?: string }> = {};

    public readonly host: string;
    public readonly port: number;
    public readonly webSocketPort: number;
    public readonly replPort: number;
    private sessionTimeoutInterval: ReturnType<typeof setInterval> | undefined;

    constructor(private isMultiUser: boolean = false) {
        // read port configuration from environment variables
        this.host = process.env.PENPOT_MCP_SERVER_HOST ?? "0.0.0.0";
        this.port = parseInt(process.env.PENPOT_MCP_SERVER_PORT ?? "4401", 10);
        this.webSocketPort = parseInt(process.env.PENPOT_MCP_WEBSOCKET_PORT ?? "4402", 10);
        this.replPort = parseInt(process.env.PENPOT_MCP_REPL_PORT ?? "4403", 10);

        this.configLoader = new ConfigurationLoader(process.cwd());
        this.apiDocs = new ApiDocs();

        this.tools = new Map<string, Tool<any>>();
        this.pluginBridge = new PluginBridge(this, this.webSocketPort);
        this.replServer = new ReplServer(this.pluginBridge, this.replPort);

        this.initTools();
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

    public getInitialInstructions(): string {
        let instructions = this.configLoader.getInitialInstructions();
        instructions = instructions.replace("$api_types", this.apiDocs.getTypeNames().join(", "));
        return instructions;
    }

    /**
     * Retrieves the current session context.
     *
     * @returns The session context for the current request, or undefined if not in a request context
     */
    public getSessionContext(): SessionContext | undefined {
        return this.sessionContext.getStore();
    }

    private initTools(): void {
        const toolInstances: Tool<any>[] = [
            new ExecuteCodeTool(this),
            new HighLevelOverviewTool(this),
            new PenpotApiInfoTool(this, this.apiDocs),
            new ExportShapeTool(this),
        ];
        if (this.isFileSystemAccessEnabled()) {
            toolInstances.push(new ImportImageTool(this));
        }

        for (const tool of toolInstances) {
            this.logger.info(`Registering tool: ${tool.getToolName()}`);
            this.tools.set(tool.getToolName(), tool);
        }
    }

    /**
     * Creates a fresh {@link McpServer} instance with all tools registered.
     */
    private createMcpServer(): McpServer {
        const server = new McpServer(
            { name: "penpot-mcp-server", version: "1.0.0" },
            { instructions: this.getInitialInstructions() }
        );

        for (const tool of this.tools.values()) {
            server.registerTool(
                tool.getToolName(),
                {
                    description: tool.getToolDescription(),
                    inputSchema: tool.getInputSchema(),
                },
                async (args) => tool.execute(args)
            );
        }

        return server;
    }

    /**
     * Starts a periodic timer that closes and removes Streamable HTTP sessions that have been
     * idle for longer than {@link SESSION_TIMEOUT_MINUTES}.
     */
    private startSessionTimeoutChecker(): void {
        const timeoutMs = PenpotMcpServer.SESSION_TIMEOUT_MINUTES * 60 * 1000;
        const checkIntervalMs = timeoutMs / 2;
        this.sessionTimeoutInterval = setInterval(() => {
            this.logger.info("Checking for stale sessions...");
            const now = Date.now();
            let removed = 0;
            for (const session of Object.values(this.streamableTransports)) {
                if (now - session.lastActiveTime > timeoutMs) {
                    session.transport.close();
                    removed++;
                }
            }
            this.logger.info(
                `Removed ${removed} stale session(s); total sessions remaining: ${Object.keys(this.streamableTransports).length}`
            );
        }, checkIntervalMs);
    }

    private setupHttpEndpoints(): void {
        /**
         * Modern Streamable HTTP connection endpoint.
         *
         * New sessions are created on initialize requests (no mcp-session-id header).
         * Subsequent requests for an existing session are routed to the stored transport,
         * with the session context populated from the stored userToken.
         */
        this.app.all("/mcp", async (req: any, res: any) => {
            const sessionId = req.headers["mcp-session-id"] as string | undefined;
            let userToken: string | undefined = undefined;
            let transport: StreamableHTTPServerTransport;

            // obtain transport and user token for the session, either from an existing session or by creating a new one
            if (sessionId && this.streamableTransports[sessionId]) {
                // existing session: reuse stored transport and token
                const session = this.streamableTransports[sessionId];
                transport = session.transport;
                userToken = session.userToken;
                session.lastActiveTime = Date.now();
                this.logger.info(
                    `Received request for existing session with id=${sessionId}; userToken=${session.userToken}`
                );
            } else {
                // new session: create a fresh McpServer and transport
                userToken = req.query.userToken as string | undefined;
                this.logger.info(`Received new session request; userToken=${userToken}`);
                const { randomUUID } = await import("node:crypto");
                const server = this.createMcpServer();
                transport = new StreamableHTTPServerTransport({
                    sessionIdGenerator: () => randomUUID(),
                    onsessioninitialized: (id) => {
                        this.streamableTransports[id] = new StreamableSession(transport, userToken, Date.now());
                        this.logger.info(
                            `Session initialized with id=${id} for userToken=${userToken}; total sessions: ${Object.keys(this.streamableTransports).length}`
                        );
                    },
                });
                transport.onclose = () => {
                    if (transport.sessionId) {
                        this.logger.info(`Closing session with id=${transport.sessionId} for userToken=${userToken}`);
                        delete this.streamableTransports[transport.sessionId];
                    }
                };
                await server.connect(transport);
            }

            // handle the request
            await this.sessionContext.run({ userToken }, async () => {
                await transport.handleRequest(req, res, req.body);
            });
        });

        /**
         * Legacy SSE connection endpoint.
         */
        this.app.get("/sse", async (req: any, res: any) => {
            const userToken = req.query.userToken as string | undefined;

            await this.sessionContext.run({ userToken }, async () => {
                const transport = new SSEServerTransport("/messages", res);
                this.sseTransports[transport.sessionId] = { transport, userToken };

                const server = this.createMcpServer();
                await server.connect(transport);
                res.on("close", () => {
                    delete this.sseTransports[transport.sessionId];
                    server.close();
                });
            });
        });

        /**
         * SSE message POST endpoint (using previously established session)
         */
        this.app.post("/messages", async (req: any, res: any) => {
            const sessionId = req.query.sessionId as string;
            const session = this.sseTransports[sessionId];

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

        return new Promise((resolve) => {
            this.app.listen(this.port, this.host, async () => {
                this.logger.info(`Multi-user mode: ${this.isMultiUserMode()}`);
                this.logger.info(`Remote mode: ${this.isRemoteMode()}`);
                this.logger.info(`Modern Streamable HTTP endpoint: http://${this.host}:${this.port}/mcp`);
                this.logger.info(`Legacy SSE endpoint: http://${this.host}:${this.port}/sse`);
                this.logger.info(`WebSocket server URL: ws://${this.host}:${this.webSocketPort}`);

                // start the REPL server and session timeout checker
                await this.replServer.start();
                this.startSessionTimeoutChecker();

                resolve();
            });
        });
    }

    /**
     * Stops the MCP server and associated services.
     *
     * Gracefully shuts down the REPL server and other components.
     */
    public async stop(): Promise<void> {
        this.logger.info("Stopping Penpot MCP Server...");
        clearInterval(this.sessionTimeoutInterval);
        await this.replServer.stop();
        this.logger.info("Penpot MCP Server stopped");
    }
}

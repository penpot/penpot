import express from "express";
import path from "path";
import { fileURLToPath } from "url";
import { PluginBridge } from "./PluginBridge";
import { ExecuteCodePluginTask } from "./tasks/ExecuteCodePluginTask";
import { createLogger } from "./logger";

/**
 * Web-based REPL server for executing code through the PluginBridge.
 *
 * Provides a REPL-style HTML interface that allows users to input
 * JavaScript code and execute it via ExecuteCodePluginTask instances.
 * The interface maintains command history, displays logs in &lt;pre&gt; tags,
 * and shows results in visually separated blocks.
 */
export class ReplServer {
    private readonly logger = createLogger("ReplServer");
    private readonly app: express.Application;
    private readonly port: number;
    private server: any;

    constructor(
        private readonly pluginBridge: PluginBridge,
        port: number = 4403
    ) {
        this.port = port;
        this.app = express();
        this.setupMiddleware();
        this.setupRoutes();
    }

    /**
     * Sets up Express middleware for request parsing and static content.
     */
    private setupMiddleware(): void {
        this.app.use(express.json());
    }

    /**
     * Sets up HTTP routes for the REPL interface and API endpoints.
     */
    private setupRoutes(): void {
        // serve the main REPL interface
        this.app.get("/", (req, res) => {
            const __filename = fileURLToPath(import.meta.url);
            const __dirname = path.dirname(__filename);
            const htmlPath = path.join(__dirname, "static", "repl.html");
            res.sendFile(htmlPath);
        });

        // API endpoint for executing code
        this.app.post("/execute", async (req, res) => {
            try {
                const { code } = req.body;

                if (!code || typeof code !== "string") {
                    return res.status(400).json({
                        error: "Code parameter is required and must be a string",
                    });
                }

                const task = new ExecuteCodePluginTask({ code });
                const result = await this.pluginBridge.executePluginTask(task);

                // extract the result member from ExecuteCodeTaskResultData
                const executeResult = result.data?.result;

                res.json({
                    success: true,
                    result: executeResult,
                    log: result.data?.log || "",
                });
            } catch (error) {
                this.logger.error(error, "Failed to execute code in REPL");
                res.status(500).json({
                    error: error instanceof Error ? error.message : "Unknown error occurred",
                });
            }
        });
    }

    /**
     * Starts the REPL web server.
     *
     * Begins listening on the configured port and logs server startup information.
     */
    public async start(): Promise<void> {
        return new Promise((resolve) => {
            this.server = this.app.listen(this.port, () => {
                this.logger.info(`REPL server started on port ${this.port}`);
                this.logger.info(
                    `REPL interface URL: http://${this.pluginBridge.mcpServer.serverAddress}:${this.port}`
                );
                resolve();
            });
        });
    }

    /**
     * Stops the REPL web server.
     */
    public async stop(): Promise<void> {
        if (this.server) {
            return new Promise((resolve) => {
                this.server.close(() => {
                    this.logger.info("REPL server stopped");
                    resolve();
                });
            });
        }
    }
}

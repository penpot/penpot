#!/usr/bin/env node

import { PenpotMcpServer } from "./PenpotMcpServer";
import { createLogger, logFilePath } from "./logger";

/**
 * Entry point for Penpot MCP Server
 *
 * Creates and starts the MCP server instance, handling any startup errors
 * gracefully and ensuring proper process termination.
 *
 * Configuration via environment variables (see README).
 */
async function main(): Promise<void> {
    const logger = createLogger("main");

    // log the file path early so it appears before any potential errors
    logger.info(`Logging to file: ${logFilePath}`);

    try {
        const args = process.argv.slice(2);
        let multiUser = false; // default to single-user mode

        // parse command line arguments
        for (let i = 0; i < args.length; i++) {
            if (args[i] === "--multi-user") {
                multiUser = true;
            } else if (args[i] === "--help" || args[i] === "-h") {
                logger.info("Usage: node dist/index.js [options]");
                logger.info("Options:");
                logger.info("  --multi-user           Enable multi-user mode (default: single-user)");
                logger.info("  --help, -h             Show this help message");
                logger.info("");
                logger.info("Note that configuration is mostly handled through environment variables.");
                logger.info("Refer to the README for more information.");
                process.exit(0);
            }
        }

        const server = new PenpotMcpServer(multiUser);
        await server.start();

        // keep the process alive
        process.on("SIGINT", async () => {
            logger.info("Received SIGINT, shutting down gracefully...");
            await server.stop();
            process.exit(0);
        });

        process.on("SIGTERM", async () => {
            logger.info("Received SIGTERM, shutting down gracefully...");
            await server.stop();
            process.exit(0);
        });
    } catch (error) {
        logger.error(error, "Failed to start MCP server");
        process.exit(1);
    }
}

// Start the server if this file is run directly
if (import.meta.url.endsWith(process.argv[1]) || process.argv[1].endsWith("index.js")) {
    main().catch((error) => {
        createLogger("main").error(error, "Unhandled error in main");
        process.exit(1);
    });
}

#!/usr/bin/env node

import { PenpotMcpServer } from "./PenpotMcpServer";
import { createLogger, logActiveTransports } from "./logger";
import { parseInstallerArgs, runInstall, runUninstall, printResults, printHelp } from "./installer";
import { runDoctor, printDoctorReport } from "./installer/doctor";
import { parseConfigArgs, runConfig } from "./installer/configCommand";

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

    // dispatch to installer subcommands (install / uninstall / doctor / help) before any server setup
    const rawArgs = process.argv.slice(2);
    const subcommand = rawArgs[0];
    const FLAG_HELP = subcommand === "--help" || subcommand === "-h";
    if (
        subcommand === "install" ||
        subcommand === "uninstall" ||
        subcommand === "doctor" ||
        subcommand === "help" ||
        FLAG_HELP ||
        subcommand === "config"
    ) {
        try {
            if (FLAG_HELP) {
                printHelp();
                process.exit(0);
            }
            if (subcommand === "config") {
                const configArgs = parseConfigArgs(rawArgs.slice(1));
                const code = await runConfig(configArgs);
                process.exit(code);
            }
            const parsed = parseInstallerArgs(rawArgs)!;
            if (parsed.command === "help") {
                printHelp();
                process.exit(0);
            }
            if (parsed.command === "doctor") {
                const report = await runDoctor(parsed.serverUrl);
                printDoctorReport(report);
                process.exit(report.healthy ? 0 : 1);
            }
            const results = parsed.command === "install" ? await runInstall(parsed) : await runUninstall(parsed);
            printResults(results);
            const hadError = results.some((r) => !!r.error);
            process.exit(hadError ? 1 : 0);
        } catch (err) {
            console.error("Error:", err instanceof Error ? err.message : String(err));
            printHelp();
            process.exit(2);
        }
    }

    // announce active transports early so they appear before any potential errors
    logActiveTransports(logger);

    try {
        const args = rawArgs;
        let multiUser = false; // default to single-user mode

        // parse command line arguments
        for (let i = 0; i < args.length; i++) {
            if (args[i] === "--multi-user") {
                multiUser = true;
            } else if (args[i] === "serve") {
                // accept as an explicit alias for the default action
                continue;
            } else if (args[i] === "--help" || args[i] === "-h") {
                printHelp();
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

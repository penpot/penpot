import pino from "pino";
import { join, resolve } from "path";

/**
 * Configuration for log file location and level.
 */
const LOG_DIR = process.env.PENPOT_MCP_LOG_DIR || "logs";
const LOG_LEVEL = process.env.PENPOT_MCP_LOG_LEVEL || "info";

/**
 * Generates a timestamped log file name.
 *
 * @returns Log file name
 */
function generateLogFileName(): string {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, "0");
    const day = String(now.getDate()).padStart(2, "0");
    const hours = String(now.getHours()).padStart(2, "0");
    const minutes = String(now.getMinutes()).padStart(2, "0");
    const seconds = String(now.getSeconds()).padStart(2, "0");
    return `penpot-mcp-${year}${month}${day}-${hours}${minutes}${seconds}.log`;
}

/**
 * Absolute path to the log file being written.
 */
export const logFilePath = resolve(join(LOG_DIR, generateLogFileName()));

/**
 * Logger instance configured for both console and file output with metadata.
 *
 * Both console and file output use pretty formatting for human readability.
 * Console output includes colors, while file output is plain text.
 */
export const logger = pino({
    level: LOG_LEVEL,
    timestamp: pino.stdTimeFunctions.isoTime,
    transport: {
        targets: [
            {
                // console transport with pretty formatting
                target: "pino-pretty",
                level: LOG_LEVEL,
                options: {
                    colorize: true,
                    translateTime: "SYS:yyyy-mm-dd HH:MM:ss.l",
                    ignore: "pid,hostname",
                    messageFormat: "{msg}",
                    levelFirst: true,
                },
            },
            {
                // file transport with pretty formatting (same as console)
                target: "pino-pretty",
                level: LOG_LEVEL,
                options: {
                    destination: logFilePath,
                    colorize: false,
                    translateTime: "SYS:yyyy-mm-dd HH:MM:ss.l",
                    ignore: "pid,hostname",
                    messageFormat: "{msg}",
                    levelFirst: true,
                    mkdir: true,
                },
            },
        ],
    },
});

/**
 * Creates a child logger with the specified name/origin.
 *
 * @param name - The name/origin identifier for the logger
 * @returns Child logger instance with the specified name
 */
export function createLogger(name: string) {
    return logger.child({ name });
}

import pino, { type TransportTargetOptions } from "pino";
import { join, resolve } from "path";

/**
 * Configured log level (defaults to `info`).
 */
const LOG_LEVEL = process.env.PENPOT_MCP_LOG_LEVEL || "info";

/**
 * Configured log directory; file logging is enabled iff this is set to a non-empty value.
 */
const LOG_DIR = process.env.PENPOT_MCP_LOG_DIR;

/**
 * Loki host URI; if set and non-empty, Loki logging is enabled.
 */
const LOKI_URI = process.env.PENPOT_LOGGERS_LOKI_URI;

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
 * The pino transport target spec, as expected in `transport.targets[]`.
 */
type TransportTargetSpec = TransportTargetOptions;

/**
 * Provides a single pino transport target, either active or inactive.
 *
 * Implementations decide their own activation based on environment configuration.
 * An inactive provider returns `null` from {@link getTarget} and is skipped.
 */
interface LogTransportProvider {
    /**
     * Returns the pino transport target spec, or `null` if this transport is disabled.
     */
    getTarget(): TransportTargetSpec | null;

    /**
     * Returns a human-readable startup message describing the transport, or `null` if disabled.
     */
    getStartupMessage(): string | null;
}

/**
 * Console transport with pretty-printed, colorized output. Always active.
 */
class ConsoleLogTransport implements LogTransportProvider {
    public getTarget(): TransportTargetSpec {
        return {
            target: "pino-pretty",
            level: LOG_LEVEL,
            options: {
                colorize: true,
                translateTime: "SYS:yyyy-mm-dd HH:MM:ss.l",
                ignore: "pid,hostname",
                messageFormat: "{msg}",
                levelFirst: true,
            },
        };
    }

    public getStartupMessage(): string {
        return "Logging to console";
    }
}

/**
 * File transport writing pretty-formatted logs to a timestamped file in a configurable directory.
 * Active iff `PENPOT_MCP_LOG_DIR` is set and non-empty.
 */
class FileLogTransport implements LogTransportProvider {
    private readonly enabled: boolean;
    private readonly filePath: string | null;

    public constructor(logDir: string | undefined) {
        this.enabled = logDir !== undefined && logDir !== "";
        this.filePath = this.enabled ? resolve(join(logDir as string, generateLogFileName())) : null;
    }

    public isEnabled(): boolean {
        return this.enabled;
    }

    public getTarget(): TransportTargetSpec | null {
        if (!this.enabled) {
            return null;
        }
        return {
            target: "pino-pretty",
            level: LOG_LEVEL,
            options: {
                destination: this.filePath,
                colorize: false,
                translateTime: "SYS:yyyy-mm-dd HH:MM:ss.l",
                ignore: "pid,hostname",
                messageFormat: "{msg}",
                levelFirst: true,
                mkdir: true,
            },
        };
    }

    public getStartupMessage(): string | null {
        return this.enabled ? `Logging to file: ${this.filePath}` : null;
    }

    /**
     * Returns the absolute path of the active log file, or `undefined` if file logging is disabled.
     */
    public getFilePath(): string | undefined {
        return this.filePath ?? undefined;
    }
}

/**
 * Loki transport forwarding logs to a Grafana Loki instance via `pino-loki`.
 *
 * Active iff `PENPOT_LOGGERS_LOKI_URI` is set and non-empty.
 */
class LokiLogTransport implements LogTransportProvider {
    private readonly host: string | null;

    public constructor(lokiUri: string | undefined) {
        this.host = lokiUri !== undefined && lokiUri !== "" ? lokiUri : null;
    }

    public isEnabled(): boolean {
        return this.host !== null;
    }

    public getTarget(): TransportTargetSpec | null {
        if (this.host === null) {
            return null;
        }
        return {
            target: "pino-loki",
            level: LOG_LEVEL,
            options: {
                host: this.host,
                json: false,
                batching: true,
                interval: 5,
                replaceTimestamp: true,
                labels: this.buildLabels(),
            },
        };
    }

    /**
     * Builds the set of static labels to attach to every log entry sent to Loki.
     *
     * The `environment` and `instance` labels are only included if their respective
     * environment variables are set and non-empty.
     */
    private buildLabels(): Record<string, string> {
        const labels: Record<string, string> = {
            job: process.env.PENPOT_LOGGERS_LOKI_JOB || "mcp",
        };
        const environment = process.env.PENPOT_LOGGERS_LOKI_ENVIRONMENT;
        if (environment) {
            labels.environment = environment;
        }
        const instance = process.env.PENPOT_LOGGERS_LOKI_INSTANCE;
        if (instance) {
            labels.instance = instance;
        }
        return labels;
    }

    public getStartupMessage(): string | null {
        return this.host !== null ? `Logging to Loki: ${this.host}` : null;
    }
}

// build the transport providers; each decides its own activation independently
const consoleTransport = new ConsoleLogTransport();
const fileTransport = new FileLogTransport(LOG_DIR);
const lokiTransport = new LokiLogTransport(LOKI_URI);

const transports: LogTransportProvider[] = [consoleTransport, fileTransport, lokiTransport];

/**
 * Absolute path to the log file being written, or `undefined` if file logging is disabled.
 */
export const logFilePath: string | undefined = fileTransport.getFilePath();

/**
 * Logger instance configured with the active transports (console, optional file, optional Loki).
 */
export const logger = pino({
    level: LOG_LEVEL,
    timestamp: pino.stdTimeFunctions.isoTime,
    transport: {
        targets: transports
            .map((t) => t.getTarget())
            .filter((target): target is TransportTargetSpec => target !== null),
    },
});

/**
 * Logs a startup line for each active transport, allowing the user to see at a glance
 * where logs are being written.
 *
 * @param log - the logger to emit the startup messages on
 */
export function logActiveTransports(log: pino.Logger): void {
    for (const t of transports) {
        const msg = t.getStartupMessage();
        if (msg !== null) {
            log.info(msg);
        }
    }
}

/**
 * Creates a child logger with the specified name/origin.
 *
 * @param name - the name/origin identifier for the logger
 * @returns child logger instance with the specified name
 */
export function createLogger(name: string) {
    return logger.child({ name });
}

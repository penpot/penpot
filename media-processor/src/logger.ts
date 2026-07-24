import pino, { type TransportTargetOptions } from "pino";
import { loadConfig } from "./config.js";
import type { AppConfig } from "./types.js";

type TransportTargetSpec = TransportTargetOptions;

interface LogTransportProvider {
  getTarget(): TransportTargetSpec | null;
  getStartupMessage(): string | null;
}

class ConsoleLogTransport implements LogTransportProvider {
  public constructor(private readonly config: AppConfig) {}

  public getTarget(): TransportTargetSpec {
    return {
      target: "pino-pretty",
      level: this.config.logLevel,
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

class LokiLogTransport implements LogTransportProvider {
  private readonly host: string | null;

  public constructor(
    private readonly config: AppConfig,
    lokiUri: string | null
  ) {
    this.host = lokiUri;
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
      level: this.config.logLevel,
      options: {
        host: this.host,
        json: false,
        batching: true,
        interval: 5,
        replaceTimestamp: true,
        labels: this.buildLabels(),
        messageFormat: "{msg}",
        ignore: "pid,hostname",
      },
    };
  }

  private buildLabels(): Record<string, string> {
    const labels: Record<string, string> = {
      job: this.config.lokiJob,
    };
    if (this.config.lokiEnvironment) {
      labels.environment = this.config.lokiEnvironment;
    }
    if (this.config.lokiInstance) {
      labels.instance = this.config.lokiInstance;
    }
    return labels;
  }

  public getStartupMessage(): string | null {
    return this.host !== null ? `Logging to Loki: ${this.host}` : null;
  }
}

function buildLogger(config: AppConfig) {
  const consoleTransport = new ConsoleLogTransport(config);
  const lokiTransport = new LokiLogTransport(config, config.lokiUri);
  const transports: LogTransportProvider[] = [consoleTransport, lokiTransport];

  const instance = pino({
    level: config.logLevel,
    timestamp: pino.stdTimeFunctions.isoTime,
    transport: {
      targets: transports.map((t) => t.getTarget()).filter((target): target is TransportTargetSpec => target !== null),
    },
  });

  return { instance, transports };
}

let _instance: pino.Logger | null = null;
let _transports: LogTransportProvider[] = [];

export function initLogger(config: AppConfig): pino.Logger {
  const result = buildLogger(config);
  _instance = result.instance;
  _transports = result.transports;
  return _instance;
}

function getInstance(): pino.Logger {
  if (_instance === null) {
    return initLogger(loadConfig());
  }
  return _instance;
}

// Export as a getter so consumers see the lazily-initialized instance.
export const logger: pino.Logger = new Proxy({} as pino.Logger, {
  get(_, prop) {
    const inst = getInstance();
    const value = (inst as unknown as Record<string | symbol, unknown>)[prop];
    return typeof value === "function" ? value.bind(inst) : value;
  },
});

export function logActiveTransports(log: pino.Logger): void {
  for (const t of _transports) {
    const msg = t.getStartupMessage();
    if (msg !== null) {
      log.info(msg);
    }
  }
}

export function createLogger(name: string) {
  return logger.child({ name });
}

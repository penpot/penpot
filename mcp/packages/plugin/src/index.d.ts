import "@penpot/plugin-types";

declare module "@penpot/plugin-types" {
    interface Penpot {
        /** The Penpot application version string. */
        version: string;
    }
}

interface McpActivityPayload {
    phase: "received" | "completed" | "failed";
    id: string;
    task: string;
    code?: string | null;
    error?: string | null;
}

interface McpOptions {
    getToken(): string;
    getServerUrl(): string;
    setMcpStatus(status: string);
    on(eventType: "disconnect" | "connect", cb: () => void);
    /** When set (hosted Penpot + remote MCP), reports task lifecycle for in-app inspection. */
    notifyActivity?(payload: McpActivityPayload): void;
}

declare global {
    const mcp: undefined | McpOptions;
}

export {};

import "@penpot/plugin-types";

declare module "@penpot/plugin-types" {
    interface Penpot {
        /** The Penpot application version string. */
        version: string;
    }
}

interface McpOptions {
    getToken(): string;
    getServerUrl(): string;
    setMcpStatus(status: string);
    on(eventType: "disconnect" | "connect", cb: () => void);
}

declare global {
    const mcp: undefined | McpOptions;
}

export {};

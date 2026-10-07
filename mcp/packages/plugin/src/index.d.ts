interface McpOptions {
    getToken(): string;
    getServerUrl(): string;
    isConnectionRequested(): boolean;
    setMcpStatus(status: string, sessionId?: string);
    on(eventType: "disconnect" | "connect", cb: () => void);
}

declare global {
    const mcp: undefined | McpOptions;
    /** management API; only exposed to plugins running with global scope */
    const penpotMgmt: undefined | null | import("../../../../plugins/libs/plugin-types").PenpotMgmt;
}

export {};

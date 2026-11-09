interface McpOptions {
    getToken(): string;
    getServerUrl(): string;
    isConnectionRequested(): boolean;
    setMcpStatus(status: string, sessionId?: string);
    on(eventType: "disconnect" | "connect", cb: () => void);
}

declare global {
    const mcp: undefined | McpOptions;
}

export {};

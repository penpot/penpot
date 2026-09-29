interface McpOptions {
    getToken(): string;
    getClientId(): string;
    getServerUrl(): string;
    setMcpStatus(status: string);
    on(eventType: "disconnect" | "connect", cb: () => void);
}

declare global {
    const mcp: undefined | McpOptions;
}

export {};

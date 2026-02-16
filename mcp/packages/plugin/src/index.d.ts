interface McpOptions {
    getToken(): string;
    getServerUrl(): string;
    setMcpStatus(status: string);
}

declare global {
    const mcp: undefined | McpOptions;
}

export {};

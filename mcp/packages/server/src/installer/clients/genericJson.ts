import { ClientInstaller, InstallOptions, InstallResult } from "../types";

export const genericJson: ClientInstaller = {
    id: "generic-json",
    label: "Generic JSON (snippet only)",
    describe() {
        return "Prints a generic MCP server JSON snippet without touching any file. Useful for clients not listed here.";
    },
    configPath() {
        return "(snippet only — no file)";
    },
    snippet(opts: InstallOptions) {
        return {
            mcpServers: {
                [opts.entryName]: {
                    url: opts.serverUrl,
                },
            },
        };
    },
    async install(opts: InstallOptions): Promise<InstallResult> {
        const snippet = this.snippet(opts);
        console.log("\n# Paste the following into your MCP client's configuration:\n");
        console.log(JSON.stringify(snippet, null, 2));
        console.log("");
        return {
            client: this.id,
            path: this.configPath(),
            written: false,
            skipped: "snippet printed; copy into your client config manually",
            snippet,
        };
    },
    async uninstall(opts: InstallOptions): Promise<InstallResult> {
        console.log(`# Remove the 'mcpServers.${opts.entryName}' entry from your MCP client's configuration manually.`);
        return {
            client: this.id,
            path: this.configPath(),
            written: false,
            skipped: "no file managed by helper",
        };
    },
};

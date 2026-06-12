import * as http from "node:http";
import * as net from "node:net";

interface Check {
    name: string;
    ok: boolean;
    detail?: string;
}

const DEFAULT_HOST = process.env.PENPOT_MCP_SERVER_HOST ?? "localhost";
const DEFAULT_PORT = parseInt(process.env.PENPOT_MCP_SERVER_PORT ?? "4401", 10);
const DEFAULT_WS_PORT = parseInt(process.env.PENPOT_MCP_WEBSOCKET_PORT ?? "4402", 10);
const DEFAULT_PLUGIN_PORT = 4400;

function probeTcp(host: string, port: number, timeoutMs = 1500): Promise<boolean> {
    return new Promise((resolve) => {
        const socket = new net.Socket();
        let done = false;
        const finish = (ok: boolean) => {
            if (done) return;
            done = true;
            try {
                socket.destroy();
            } catch {
                // ignore
            }
            resolve(ok);
        };
        socket.setTimeout(timeoutMs);
        socket.once("connect", () => finish(true));
        socket.once("timeout", () => finish(false));
        socket.once("error", () => finish(false));
        socket.connect(port, host);
    });
}

function probeHttp(url: string, timeoutMs = 1500): Promise<{ ok: boolean; status?: number; body?: string }> {
    return new Promise((resolve) => {
        const req = http.get(url, { timeout: timeoutMs }, (res) => {
            let body = "";
            res.on("data", (chunk) => {
                if (body.length < 2048) {
                    body += chunk.toString();
                }
            });
            res.on("end", () => resolve({ ok: true, status: res.statusCode, body }));
        });
        req.on("timeout", () => {
            req.destroy();
            resolve({ ok: false });
        });
        req.on("error", () => resolve({ ok: false }));
    });
}

export async function runDoctor(serverUrlOverride?: string): Promise<{ checks: Check[]; healthy: boolean }> {
    const host = DEFAULT_HOST;
    const port = DEFAULT_PORT;
    const wsPort = DEFAULT_WS_PORT;
    const pluginPort = DEFAULT_PLUGIN_PORT;
    const checks: Check[] = [];

    const mcpHttpOk = await probeTcp(host, port);
    checks.push({
        name: `MCP HTTP port (${host}:${port})`,
        ok: mcpHttpOk,
        detail: mcpHttpOk ? "open" : "closed — server not running?",
    });

    const wsOk = await probeTcp(host, wsPort);
    checks.push({
        name: `Plugin WebSocket port (${host}:${wsPort})`,
        ok: wsOk,
        detail: wsOk ? "open" : "closed — server not running?",
    });

    const pluginOk = await probeTcp(host, pluginPort);
    checks.push({
        name: `Plugin web server port (${host}:${pluginPort})`,
        ok: pluginOk,
        detail: pluginOk ? "open" : "closed — plugin server not running?",
    });

    if (pluginOk) {
        const manifest = await probeHttp(`http://${host}:${pluginPort}/manifest.json`);
        checks.push({
            name: "Plugin manifest reachable",
            ok: manifest.ok && manifest.status === 200,
            detail: manifest.ok && manifest.status === 200 ? "200 OK" : `status=${manifest.status ?? "n/a"}`,
        });
    }

    if (mcpHttpOk) {
        const mcpUrl = serverUrlOverride ?? `http://${host}:${port}/mcp`;
        const probe = await probeHttp(mcpUrl);
        checks.push({
            name: `MCP endpoint reachable (${mcpUrl})`,
            ok: probe.ok,
            detail: probe.ok ? `status=${probe.status ?? "n/a"}` : "connection failed",
        });
    }

    const healthy = checks.every((c) => c.ok);
    return { checks, healthy };
}

export function printDoctorReport(report: { checks: Check[]; healthy: boolean }): void {
    console.log("Penpot MCP doctor");
    console.log("-----------------");
    for (const c of report.checks) {
        const mark = c.ok ? "OK  " : "FAIL";
        console.log(`[${mark}] ${c.name}${c.detail ? `  — ${c.detail}` : ""}`);
    }
    console.log("-----------------");
    console.log(report.healthy ? "Overall: healthy" : "Overall: issues detected");
    if (!report.healthy) {
        console.log("\nHints:");
        console.log("  • Start the server first: `npx -y @penpot/mcp@latest`");
        console.log("  • Make sure the Penpot MCP plugin UI in your browser is open and connected.");
        console.log("  • Plugin UI must stay open; closing it disconnects the WebSocket.");
    }
}

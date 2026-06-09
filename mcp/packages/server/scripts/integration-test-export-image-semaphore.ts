/**
 * One-off integration test for the parallelism bound around image exports.
 *
 * Setup:
 *  - Stubs ExportShapeTool.exportImage to sleep SLEEP_MS instead of doing real work,
 *    so no actual plugin connection is needed.
 *  - Replaces the static parallelism semaphore with one of size N.
 *  - Starts a PenpotMcpServer in multi-user mode on three random free ports.
 *  - Fires M > N parallel MCP clients that each call the export_shape tool.
 *
 * Expectations (observed manually from the server's console output):
 *  - "Semaphore 'ExportShapeTool' saturated; request queued (k waiting)" lines appear
 *    at INFO level (at least M - N of them).
 *  - All M tool calls return successfully.
 *  - Total elapsed wall-clock time is approximately ceil(M / N) * SLEEP_MS.
 *
 * Invoke from packages/server with:
 *   pnpm run test:integration:export
 */
import * as net from "node:net";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

import { PenpotMcpServer } from "../src/PenpotMcpServer";
import { ExportShapeTool } from "../src/tools/ExportShapeTool";
import { TextResponse } from "../src/ToolResponse";
import { Semaphore } from "../src/utils/Semaphore";

// === parameters ===

const N = 3;
const M = 6;
const SLEEP_MS = 5_000;

// === helpers ===

async function findFreePort(): Promise<number> {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.unref();
        srv.on("error", reject);
        srv.listen(0, "127.0.0.1", () => {
            const port = (srv.address() as net.AddressInfo).port;
            srv.close(() => resolve(port));
        });
    });
}

async function callExportShape(url: URL, idx: number): Promise<unknown> {
    const client = new Client({ name: `integration-test-client-${idx}`, version: "0.0.0" });
    const transport = new StreamableHTTPClientTransport(url);
    await client.connect(transport);
    try {
        return await client.callTool({
            name: "export_shape",
            arguments: { shapeId: "selection" },
        });
    } finally {
        await client.close();
    }
}

// === main ===

async function main(): Promise<void> {
    // dynamic ports must be set before PenpotMcpServer is constructed
    const httpPort = await findFreePort();
    process.env.PENPOT_MCP_SERVER_HOST = "127.0.0.1";
    process.env.PENPOT_MCP_SERVER_PORT = String(httpPort);
    process.env.PENPOT_MCP_WEBSOCKET_PORT = String(await findFreePort());
    process.env.PENPOT_MCP_REPL_PORT = String(await findFreePort());

    // shrink the gate and stub the worker
    (ExportShapeTool as any).parallelismSemaphore = new Semaphore("ExportShapeTool", N);
    (ExportShapeTool.prototype as any).exportImage = async (): Promise<TextResponse> => {
        await new Promise((r) => setTimeout(r, SLEEP_MS));
        return new TextResponse("stubbed export");
    };

    const server = new PenpotMcpServer(/* isMultiUser */ true);
    await server.start();

    console.log(`\n=== integration test: N=${N} permits, M=${M} clients, sleep=${SLEEP_MS}ms ===\n`);

    // fire M parallel tool calls, each via its own MCP session with a distinct userToken
    const start = Date.now();
    const results = await Promise.allSettled(
        Array.from({ length: M }, (_, i) =>
            callExportShape(new URL(`http://127.0.0.1:${httpPort}/mcp?userToken=test-token-${i}`), i)
        )
    );
    const elapsed = Date.now() - start;

    await server.stop();

    // report
    const failures = results.map((r, i) => ({ r, i })).filter(({ r }) => r.status === "rejected");

    console.log(`\n=== results ===`);
    console.log(`  elapsed: ${elapsed}ms (expected ~${Math.ceil(M / N) * SLEEP_MS}ms)`);
    console.log(`  succeeded: ${M - failures.length}/${M}`);
    if (failures.length > 0) {
        console.log(`  failures:`);
        for (const { r, i } of failures) {
            console.log(`    [${i}] ${(r as PromiseRejectedResult).reason}`);
        }
        process.exit(1);
    }
    console.log(`\nAll ${M} tool calls succeeded. Scroll up to verify saturation log lines.\n`);
    process.exit(0);
}

main().catch((err) => {
    console.error("Integration test crashed:", err);
    process.exit(1);
});

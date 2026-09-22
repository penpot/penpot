import { createServer, type Server } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, join, resolve, sep } from "node:path";

// Zero-dependency static file server for the mocked-backend CI driver.
//
// It replaces `frontend/scripts/e2e-server.js` (express-based) on purpose:
// that script resolves `express`/`compression` from `frontend/node_modules`,
// which the CI jobs never install (they only run `pnpm install` inside
// `plugins/` and restore the prebuilt bundle), so the driver crashed with
// ERR_MODULE_NOT_FOUND and timed out waiting for localhost:3000. Serving the
// bundle from here keeps the suite runnable with only `plugins/`
// dependencies — the documented local workflow — and identical in CI.
//
// NOTE on provenance: this file is duplicated in
// `plugins/apps/plugin-api-test-suite/ci/static-server.ts`. Keep the two in
// sync (same as the mock harness in `run-ci.ts`).

const MIME_TYPES: Record<string, string> = {
    ".css": "text/css; charset=utf-8",
    ".gif": "image/gif",
    ".html": "text/html; charset=utf-8",
    ".ico": "image/x-icon",
    ".jpeg": "image/jpeg",
    ".jpg": "image/jpeg",
    ".js": "application/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".map": "application/json; charset=utf-8",
    ".mjs": "application/javascript; charset=utf-8",
    ".otf": "font/otf",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ttf": "font/ttf",
    ".txt": "text/plain; charset=utf-8",
    ".wasm": "application/wasm",
    ".webmanifest": "application/manifest+json",
    ".webp": "image/webp",
    ".woff": "font/woff",
    ".woff2": "font/woff2",
    ".xml": "application/xml; charset=utf-8",
};

const INDEX = "index.html";

export interface StaticServer {
    /** Base URL the server listens on (e.g. `http://localhost:3000`). */
    url: string;
    /** Stop accepting connections; safe to call more than once. */
    close: () => Promise<void>;
}

/**
 * Serve `root` over HTTP on `port` (`0` picks a free port, reported in
 * `url`). Directory requests fall back to `index.html`; the app uses hash
 * routing, so no other fallback is needed.
 */
export function startStaticServer(root: string, port: number): Promise<StaticServer> {
    const docRoot = resolve(root);
    const server: Server = createServer(async (req, res) => {
        try {
            if (req.method !== "GET" && req.method !== "HEAD") {
                res.writeHead(405, { "Content-Type": "text/plain; charset=utf-8" });
                res.end("Method Not Allowed");
                return;
            }

            const rawPath = (req.url ?? "/").split(/[?#]/, 1)[0] ?? "/";
            let pathname: string;
            try {
                pathname = decodeURIComponent(rawPath);
            } catch {
                res.writeHead(400, { "Content-Type": "text/plain; charset=utf-8" });
                res.end("Bad Request");
                return;
            }

            const resolved = resolve(docRoot, `.${sep}${pathname}`);
            if (resolved !== docRoot && !resolved.startsWith(docRoot + sep)) {
                res.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
                res.end("Forbidden");
                return;
            }

            let filePath = resolved;
            const info = await stat(filePath).catch((error: unknown) => {
                if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
                throw error;
            });
            if (info === null) {
                res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
                res.end("Not Found");
                return;
            }
            if (info.isDirectory()) {
                filePath = join(filePath, INDEX);
            }

            const body = await readFile(filePath).catch((error: unknown) => {
                if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
                throw error;
            });
            if (body === null) {
                res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
                res.end("Not Found");
                return;
            }

            const contentType = MIME_TYPES[extname(filePath).toLowerCase()] ?? "application/octet-stream";
            res.writeHead(200, {
                "Content-Type": contentType,
                "Content-Length": body.length,
            });
            res.end(req.method === "GET" ? body : undefined);
        } catch {
            if (!res.headersSent) {
                res.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
            }
            res.end("Internal Server Error");
        }
    });

    return new Promise((fulfill, reject) => {
        server.once("error", reject);
        server.listen(port, "0.0.0.0", () => {
            server.off("error", reject);
            const address = server.address();
            const actualPort = typeof address === "object" && address !== null ? address.port : port;
            fulfill({
                url: `http://localhost:${actualPort}`,
                close: () =>
                    new Promise<void>((done, fail) => {
                        if (!server.listening) {
                            done();
                            return;
                        }
                        server.close((error) => (error ? fail(error) : done()));
                    }),
            });
        });
    });
}

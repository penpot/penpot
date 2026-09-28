import { strict as assert } from "node:assert";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, it } from "node:test";
import { startStaticServer, type StaticServer } from "./static-server.ts";

describe("static-server", () => {
    let dir: string = "";
    let server: StaticServer | undefined;
    const baseUrl = (): string => {
        if (!server) throw new Error("static server not started");
        return server.url;
    };

    before(async () => {
        dir = await mkdtemp(join(tmpdir(), "penpot-static-server-"));
        await mkdir(join(dir, "js"), { recursive: true });
        await writeFile(join(dir, "index.html"), "<!doctype html><html></html>");
        await writeFile(join(dir, "js", "app.js"), 'console.log("hi");');
        await writeFile(join(dir, "data.bin"), Buffer.from([0, 1, 2]));
        server = await startStaticServer(dir, 0);
    });

    after(async () => {
        await server?.close();
        // Closing twice must be safe (the driver closes unconditionally).
        await server?.close();
        await rm(dir, { recursive: true, force: true });
    });

    it("serves / as index.html", async () => {
        const res = await fetch(`${baseUrl()}/`);
        assert.equal(res.status, 200);
        assert.match(res.headers.get("content-type") ?? "", /text\/html/);
        assert.match(await res.text(), /<!doctype html>/);
    });

    it("serves nested files with a javascript content type", async () => {
        const res = await fetch(`${baseUrl()}/js/app.js`);
        assert.equal(res.status, 200);
        assert.match(res.headers.get("content-type") ?? "", /javascript/);
        assert.equal(await res.text(), 'console.log("hi");');
    });

    it("ignores query strings", async () => {
        const res = await fetch(`${baseUrl()}/index.html?rev=123`);
        assert.equal(res.status, 200);
        assert.match(await res.text(), /<!doctype html>/);
    });

    it("falls back to octet-stream for unknown extensions", async () => {
        const res = await fetch(`${baseUrl()}/data.bin`);
        assert.equal(res.status, 200);
        assert.equal(res.headers.get("content-type"), "application/octet-stream");
    });

    it("answers HEAD without a body", async () => {
        const res = await fetch(`${baseUrl()}/index.html`, { method: "HEAD" });
        assert.equal(res.status, 200);
        assert.match(res.headers.get("content-type") ?? "", /text\/html/);
        assert.equal(await res.text(), "");
    });

    it("rejects other methods", async () => {
        const res = await fetch(`${baseUrl()}/index.html`, { method: "POST" });
        assert.equal(res.status, 405);
    });

    it("returns 404 for missing files", async () => {
        const res = await fetch(`${baseUrl()}/nope/missing.js`);
        assert.equal(res.status, 404);
    });

    it("blocks path traversal outside the root", async () => {
        const res = await fetch(`${baseUrl()}/..%2f..%2fsecret`);
        assert.equal(res.status, 403);
    });
});

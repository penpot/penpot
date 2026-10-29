import { afterEach, describe, expect, it } from "vitest";
import type { AddressInfo } from "node:net";
import type { Server } from "node:http";
import { createHealthServer, isHealthRequest } from "../src/health.js";

describe("isHealthRequest", () => {
  it("matches the pathname and ignores the query string", () => {
    expect(isHealthRequest("GET", "/health")).toBe(true);
    expect(isHealthRequest("GET", "/health?probe=1")).toBe(true);
  });

  it("accepts a HEAD probe", () => {
    expect(isHealthRequest("HEAD", "/health")).toBe(true);
    expect(isHealthRequest("HEAD", "/health?probe=1")).toBe(true);
  });

  it("rejects another path, another method and a missing url", () => {
    expect(isHealthRequest("GET", "/other")).toBe(false);
    expect(isHealthRequest("POST", "/health")).toBe(false);
    expect(isHealthRequest("HEAD", "/other")).toBe(false);
    expect(isHealthRequest("GET", undefined)).toBe(false);
  });
});

describe("createHealthServer", () => {
  let server: Server | null = null;

  afterEach(async () => {
    if (server !== null) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server!.close(() => resolve()));
      server = null;
    }
  });

  async function start(): Promise<string> {
    server = createHealthServer();
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const { port } = server.address() as AddressInfo;
    return `http://127.0.0.1:${port}`;
  }

  it("answers 200 on the health path with a query string", async () => {
    const uri = await start();
    const response = await fetch(`${uri}/health?probe=1`);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true });
  });

  it("answers 200 to a HEAD probe without a body", async () => {
    const uri = await start();
    const response = await fetch(`${uri}/health`, { method: "HEAD" });
    expect(response.status).toBe(200);
    expect(await response.text()).toBe("");
  });

  it("answers 404 for another path or method", async () => {
    const uri = await start();
    expect((await fetch(`${uri}/other`)).status).toBe(404);
    expect((await fetch(`${uri}/health`, { method: "POST" })).status).toBe(404);
  });
});

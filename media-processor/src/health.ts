import { createServer, type Server } from "node:http";

/**
 * The healthcheck path. It matches on the pathname only, so a probe with a
 * query string (`/health?probe=1`) still counts as healthy; a load balancer
 * that appends one must not mark a live worker as down. `HEAD` is accepted
 * too: a probe may fetch the headers only, and a 404 would mark the worker as
 * down.
 */
export function isHealthRequest(method: string | undefined, url: string | undefined): boolean {
  if ((method !== "GET" && method !== "HEAD") || !url) {
    return false;
  }
  try {
    return new URL(url, "http://localhost").pathname === "/health";
  } catch {
    return false;
  }
}

export function createHealthServer(): Server {
  return createServer((request, response) => {
    if (isHealthRequest(request.method, request.url)) {
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ ok: true }));
      return;
    }
    response.writeHead(404);
    response.end();
  });
}

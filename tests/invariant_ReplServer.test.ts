import request from "supertest";
import { ReplServer } from "../../../mcp/packages/server/src/ReplServer";

describe("POST /execute requires authentication — unauthenticated requests must be rejected", () => {
  let server: ReplServer;
  let app: Express.Application;

  beforeAll(() => {
    const mockPluginBridge = { executePluginTask: jest.fn() };
    server = new ReplServer(mockPluginBridge as any);
    app = (server as any).app;
  });

  const payloads = [
    // Exact exploit: arbitrary code execution with no auth
    { label: "no auth header", code: "penpot.currentPage", headers: {} },
    // Boundary: malformed/expired token
    { label: "malformed token", code: "1+1", headers: { Authorization: "Bearer invalid.token.xyz" } },
    // Valid-looking input but still no real credentials
    { label: "empty bearer", code: "console.log('x')", headers: { Authorization: "Bearer " } },
  ];

  test.each(payloads)("rejects unauthenticated request: $label", async ({ code, headers }) => {
    const res = await request(app)
      .post("/execute")
      .set(headers)
      .send({ code });

    expect([401, 403]).toContain(res.status);
  });
});
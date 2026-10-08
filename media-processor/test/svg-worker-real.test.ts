import { describe, it, expect } from "vitest";
import { Worker } from "node:worker_threads";

// Exercises the *real* worker file (not the injectable fake used by
// `svg-pool.test.ts`): the entry point, its imports and the message protocol
// between the pool and the worker. It loads the `.ts` source with the dev
// loader (`tsx`), which is the same worker the service runs under `tsx` and
// whose compiled twin the bundle ships as `dist/svg-worker.js`.
interface WorkerReply {
  ok: boolean;
  clean?: string;
  statusCode?: number;
  body?: { code?: string };
}

function runRealWorker(svg: string): Promise<WorkerReply> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("../src/svg-worker.ts", import.meta.url), {
      execArgv: ["--import", "tsx"],
    });
    worker.once("message", (message) => {
      void worker.terminate();
      resolve(message as WorkerReply);
    });
    worker.once("error", reject);
    worker.postMessage(svg);
  });
}

describe("svg worker (real)", () => {
  it("sanitizes a malicious svg", async () => {
    const reply = await runRealWorker(
      `<svg xmlns="http://www.w3.org/2000/svg"><script>alert("x")</script><rect/></svg>`
    );
    expect(reply.ok).toBe(true);
    expect(reply.clean).toMatch(/^<svg[\s/>]/i);
    expect(reply.clean).not.toMatch(/<script/i);
  });

  it("reports invalid input as a validation error", async () => {
    const reply = await runRealWorker("this is not an svg");
    expect(reply.ok).toBe(false);
    expect(reply.statusCode).toBe(400);
    expect(reply.body?.code).toBe("invalid-svg-file");
  });
});

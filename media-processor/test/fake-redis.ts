import type { RedisLike } from "../src/jobs-worker.js";

interface Waiter {
  conn: FakeConnection;
  resolve: (value: [string, string] | null) => void;
  timer: NodeJS.Timeout;
}

/**
 * The smallest Redis the job pollers need: lists and blocking pops. It is a
 * test double, not a Redis emulator; it fails loudly when a test asks for a
 * command it does not model.
 */
export class FakeRedisServer {
  private readonly lists = new Map<string, string[]>();
  private readonly waiters = new Map<string, Waiter[]>();

  public connect(): FakeConnection {
    return new FakeConnection(this);
  }

  public push(key: string, value: string): number {
    const list = this.lists.get(key) ?? [];
    list.push(value);
    this.lists.set(key, list);
    this.wake(key);
    return list.length;
  }

  /** Test helper: block on a key and return the next value, or null. */
  public async take(key: string, timeoutSeconds = 1): Promise<string | null> {
    const answer = await this.connect().blpop(key, timeoutSeconds);
    return answer?.[1] ?? null;
  }

  public blpop(conn: FakeConnection, key: string, timeoutSeconds: number): Promise<[string, string] | null> {
    const list = this.lists.get(key);
    if (list && list.length > 0) {
      return Promise.resolve([key, list.shift()!]);
    }
    return new Promise((resolve) => {
      let waiter: Waiter;
      const timer = setTimeout(
        () => {
          this.removeWaiter(key, waiter);
          resolve(null);
        },
        Math.max(0, timeoutSeconds * 1000)
      );
      waiter = {
        conn,
        timer,
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
      };
      const waiters = this.waiters.get(key) ?? [];
      waiters.push(waiter);
      this.waiters.set(key, waiters);
    });
  }

  public close(conn: FakeConnection): void {
    for (const [key, waiters] of this.waiters) {
      const keep: Waiter[] = [];
      for (const waiter of waiters) {
        if (waiter.conn === conn) {
          waiter.resolve(null);
        } else {
          keep.push(waiter);
        }
      }
      if (keep.length > 0) {
        this.waiters.set(key, keep);
      } else {
        this.waiters.delete(key);
      }
    }
  }

  private wake(key: string): void {
    const waiters = this.waiters.get(key);
    const list = this.lists.get(key);
    if (!waiters || !list) {
      return;
    }
    while (waiters.length > 0 && list.length > 0) {
      const waiter = waiters.shift()!;
      waiter.resolve([key, list.shift()!]);
    }
    if (waiters.length === 0) {
      this.waiters.delete(key);
    }
  }

  private removeWaiter(key: string, waiter: Waiter): void {
    const waiters = this.waiters.get(key);
    if (!waiters) {
      return;
    }
    const next = waiters.filter((candidate) => candidate !== waiter);
    if (next.length > 0) {
      this.waiters.set(key, next);
    } else {
      this.waiters.delete(key);
    }
  }
}

class FakeConnection implements RedisLike {
  private closed = false;

  public constructor(private readonly server: FakeRedisServer) {}

  public blpop(key: string, timeout: number): Promise<[string, string] | null> {
    if (this.closed) {
      return Promise.resolve(null);
    }
    return this.server.blpop(this, key, timeout);
  }

  public quit(): Promise<unknown> {
    this.closed = true;
    this.server.close(this);
    return Promise.resolve("OK");
  }
}

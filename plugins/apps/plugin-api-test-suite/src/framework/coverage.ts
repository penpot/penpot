import { STATIC_COVERAGE } from './static-coverage';
import type { ApiSurface, CoverageReport, InterfaceCoverage } from './types';

export interface Recorder<T> {
  /** Proxy to hand to tests; mirrors `root` but records member access. */
  proxy: T;
  /** Every `Interface.member` pair touched through the proxy. */
  accessed: Set<string>;
  /**
   * Wraps an already-obtained value as a given interface so subsequent access
   * through it is recorded, without crediting how it was obtained. Used for the
   * scratch board, whose creation is harness bookkeeping, not test coverage.
   */
  wrap<V>(value: V, typeName: string): V;
}

function isWrappable(value: unknown): value is object {
  return (
    value !== null && (typeof value === 'object' || typeof value === 'function')
  );
}

/**
 * True when `prop` is a non-configurable, non-writable data property of `target`.
 * The Proxy `get` invariant requires returning that exact value, so wrapping it
 * is not allowed.
 */
function nonConfigurableData(target: object, prop: PropertyKey): boolean {
  const desc = Reflect.getOwnPropertyDescriptor(target, prop);
  return (
    !!desc &&
    desc.configurable === false &&
    desc.writable === false &&
    !desc.get &&
    !desc.set
  );
}

/**
 * Wraps `root` (the real `penpot` API) in a recursive Proxy that records member
 * access in a *type-aware* way. Each proxy is tagged with the interface (or union
 * alias) name the underlying value has, derived from the API type graph. When a
 * member is accessed we record `Interface.member` against the interface that
 * actually declares it, and we tag the returned value with the member's declared
 * type so nested access is attributed correctly too.
 *
 * This avoids the false positives of name-only matching, where e.g. reading
 * `shape.id` would wrongly credit every interface that happens to have an `id`
 * member. Unknown/primitive types are returned unwrapped and never recorded.
 */
export function createRecorder<T extends object>(
  root: T,
  surface: ApiSurface,
): Recorder<T> {
  const accessed = new Set<string>();
  const toOriginal = new WeakMap<object, object>();
  // Cache proxies per (target, typeName) so identity is stable and cycles end.
  const cache = new WeakMap<object, Map<string, object>>();

  function unwrap(value: unknown): unknown {
    if (isWrappable(value) && toOriginal.has(value)) {
      return toOriginal.get(value);
    }
    return value;
  }

  /** Resolves the concrete interface name for a tagged value (handles unions). */
  function concreteType(target: object, typeName: string): string | null {
    if (surface.graph[typeName]) return typeName;

    const union = surface.unions[typeName];
    if (union?.discriminant) {
      const disc = Reflect.get(target, union.discriminant.field) as unknown;
      if (typeof disc === 'string') {
        return union.discriminant.map[disc] ?? null;
      }
    }
    return null;
  }

  function wrapValue(
    value: unknown,
    typeName: string | null,
    array: boolean,
  ): unknown {
    if (!isWrappable(value) || !typeName) return value;
    if (array) {
      return Array.isArray(value) ? wrapArray(value, typeName) : value;
    }
    if (surface.graph[typeName] || surface.unions[typeName]) {
      return wrapObject(value, typeName);
    }
    return value;
  }

  function wrapArray(arr: unknown[], elementType: string): unknown[] {
    const proxy = new Proxy(arr, {
      get(tgt, prop, receiver): unknown {
        const value = Reflect.get(tgt, prop, receiver);
        if (typeof prop === 'string' && /^\d+$/.test(prop)) {
          // A frozen array (e.g. the selection array, sealed by SES) has
          // non-configurable, non-writable elements. The Proxy invariant then
          // forbids returning a wrapped value that differs from the target's,
          // so return the raw element (it just isn't credited for coverage).
          if (nonConfigurableData(tgt, prop)) return value;
          return wrapValue(value, elementType, false);
        }
        return value;
      },
    });
    toOriginal.set(proxy, arr);
    return proxy;
  }

  function wrapObject(target: object, typeName: string): object {
    let byType = cache.get(target);
    if (!byType) {
      byType = new Map();
      cache.set(target, byType);
    }
    const cached = byType.get(typeName);
    if (cached) return cached;

    const proxy: object = new Proxy(target, {
      get(tgt, prop, receiver): unknown {
        const concrete = concreteType(tgt, typeName);
        const entry =
          concrete && typeof prop === 'string'
            ? surface.graph[concrete]?.[prop]
            : undefined;

        const raw = Reflect.get(tgt, prop, receiver === proxy ? tgt : receiver);

        // Methods are credited on call (see wrapMethod), not on access. Property
        // reads are credited here as `#get`.
        if (entry && entry.kind === 'method') {
          return wrapMethod(raw as (...a: unknown[]) => unknown, tgt, {
            ...entry,
            member: String(prop),
          });
        }
        if (entry) accessed.add(`${entry.decl}.${String(prop)}#get`);

        // Don't wrap a frozen own property (Proxy invariant would be violated).
        if (typeof prop === 'string' && nonConfigurableData(tgt, prop)) {
          return raw;
        }

        return entry ? wrapValue(raw, entry.type, entry.array) : raw;
      },
      set(tgt, prop, value, receiver): boolean {
        const concrete = concreteType(tgt, typeName);
        const entry =
          concrete && typeof prop === 'string'
            ? surface.graph[concrete]?.[prop]
            : undefined;
        if (entry) accessed.add(`${entry.decl}.${String(prop)}#set`);

        return Reflect.set(
          tgt,
          prop,
          unwrap(value),
          receiver === proxy ? tgt : receiver,
        );
      },
    });

    toOriginal.set(proxy, target);
    byType.set(typeName, proxy);
    return proxy;
  }

  function wrapMethod(
    fn: (...a: unknown[]) => unknown,
    self: object,
    entry: {
      decl: string;
      member: string;
      type: string | null;
      array: boolean;
    },
  ): (...a: unknown[]) => unknown {
    return (...args: unknown[]) => {
      // Credit the call only once it returns without throwing, so coverage
      // means "successfully exercised" rather than "merely invoked".
      const result = fn.apply(self, args.map(unwrap));
      accessed.add(`${entry.decl}.${entry.member}#call`);

      // Async API methods (e.g. uploadMediaUrl, createShapeFromSvgWithImages)
      // return a Promise. Wrapping the Promise itself as the declared type would
      // break `await` (then() called on the proxy is an incompatible receiver),
      // so resolve it first and wrap the resolved value instead.
      if (
        isWrappable(result) &&
        typeof (result as { then?: unknown }).then === 'function'
      ) {
        return Promise.resolve(result as Promise<unknown>).then((value) =>
          wrapValue(value, entry.type, entry.array),
        );
      }

      return wrapValue(result, entry.type, entry.array);
    };
  }

  return {
    proxy: wrapObject(root, 'Penpot') as T,
    accessed,
    wrap: <V>(value: V, typeName: string) =>
      wrapValue(value, typeName, false) as V,
  };
}

/**
 * Compares the recorded `Interface.member` pairs against the public API surface
 * and produces a report grouped by interface. The denominator is each
 * interface's own declared members.
 */
export function computeCoverage(
  accessed: Set<string>,
  surface: ApiSurface,
): CoverageReport {
  const byInterface: Record<string, InterfaceCoverage> = {};
  let total = 0;
  let coveredCount = 0;
  let staticCount = 0;

  for (const [iface, members] of Object.entries(surface.interfaces)) {
    const all: string[] = [];
    const covered: string[] = [];
    const staticallyCovered: string[] = [];
    const uncovered: string[] = [];

    for (const member of members) {
      // Each writable property contributes separate get/set targets; read-only
      // properties only get; methods only call.
      const kind = surface.graph[iface]?.[member]?.kind ?? 'getset';
      const targets: { mode: string; label: string }[] =
        kind === 'method'
          ? [{ mode: 'call', label: `${member}()` }]
          : kind === 'get'
            ? [{ mode: 'get', label: member }]
            : [
                { mode: 'get', label: `${member} (get)` },
                { mode: 'set', label: `${member} (set)` },
              ];

      for (const { mode, label } of targets) {
        all.push(label);
        total += 1;
        const key = `${iface}.${member}#${mode}`;
        if (accessed.has(key)) {
          covered.push(label);
          coveredCount += 1;
        } else if (STATIC_COVERAGE.has(key)) {
          staticallyCovered.push(label);
          staticCount += 1;
        } else {
          uncovered.push(label);
        }
      }
    }

    byInterface[iface] = {
      members: all,
      covered,
      staticallyCovered,
      uncovered,
    };
  }

  const percent =
    total === 0 ? 100 : Math.round((coveredCount / total) * 1000) / 10;
  const effectivePercent =
    total === 0
      ? 100
      : Math.round(((coveredCount + staticCount) / total) * 1000) / 10;

  return {
    total,
    covered: coveredCount,
    staticallyCovered: staticCount,
    percent,
    effectivePercent,
    byInterface,
  };
}

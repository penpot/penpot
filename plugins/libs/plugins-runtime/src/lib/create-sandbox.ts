import type { Penpot } from '@penpot/plugin-types';
import type { createPluginManager } from './plugin-manager';
import { createApi } from './api';
import { ses } from './ses.js';

/**
 * WeakMap used to track errors originating from plugin code.
 * Using a WeakMap is safer than extending error objects because:
 * 1. It works even if the error object is frozen (SES hardened environment)
 * 2. It doesn't require modifying the error object
 * 3. It allows garbage collection of error objects when no longer referenced
 */
const pluginErrors = new WeakMap<object, true>();

/**
 * Checks if an error originated from plugin code.
 */
export function isPluginError(error: unknown): boolean {
  if (error !== null && typeof error === 'object') {
    return pluginErrors.has(error as object);
  }
  return false;
}

/**
 * Marks an error as originating from plugin code.
 * Uses a WeakMap so it works even if the error object is frozen.
 */
function markPluginError(error: unknown): void {
  if (error !== null && typeof error === 'object') {
    pluginErrors.set(error as object, true);
  }
}

/**
 * Wraps a handler function to mark any thrown errors as plugin errors.
 * Errors are marked and re-thrown so they propagate to the global error handler,
 * where they can be identified and handled appropriately.
 */
function wrapHandler<T extends (...args: unknown[]) => unknown>(
  handler: T,
): (...args: Parameters<T>) => ReturnType<T> {
  return function (...args: Parameters<T>) {
    try {
      const result = handler(...args);
      // Handle async functions - mark errors in the returned promise
      if (result instanceof Promise) {
        return result.catch((error: unknown) => {
          markPluginError(error);
          throw error;
        }) as ReturnType<T>;
      }
      return result as ReturnType<T>;
    } catch (error) {
      markPluginError(error);
      throw error;
    }
  };
}

export function createSandbox(
  plugin: Awaited<ReturnType<typeof createPluginManager>>,
  apiExtensions?: object,
) {
  ses.hardenIntrinsics();

  const pluginApi = createApi(plugin);

  const safeHandler = {
    get(target: Penpot, prop: string, receiver: unknown) {
      const originalValue = Reflect.get(target, prop, receiver);

      if (typeof originalValue === 'function') {
        return function (...args: unknown[]) {
          const result = originalValue.apply(target, args);

          return ses.safeReturn(result);
        };
      }

      return ses.safeReturn(originalValue);
    },
  };

  const proxyApi = new Proxy(pluginApi.penpot, safeHandler);

  const safeFetch = (url: string, options: RequestInit) => {
    const sanitizedOptions: RequestInit = {
      ...options,
      credentials: 'omit',
      headers: {
        ...options?.headers,
        Authorization: '',
      },
    };

    return fetch(url, sanitizedOptions).then((response) => {
      const safeResponse = {
        ok: response.ok,
        status: response.status,
        statusText: response.statusText,
        url: response.url,
        text: response.text.bind(response),
        json: response.json.bind(response),
      };

      return ses.safeReturn(safeResponse);
    });
  };

  let publicPluginApi = {
    penpot: proxyApi,
    fetch: ses.harden(safeFetch),
    setTimeout: ses.harden(
      (...[handler, timeout]: Parameters<typeof setTimeout>) => {
        const wrappedHandler = wrapHandler(
          typeof handler === 'function' ? handler : () => {},
        );
        const timeoutId = setTimeout(wrappedHandler, timeout);

        plugin.timeouts.add(timeoutId);

        return ses.safeReturn(timeoutId);
      },
    ) as typeof setTimeout,
    clearTimeout: ses.harden((id: ReturnType<typeof setTimeout>) => {
      clearTimeout(id);

      plugin.timeouts.delete(id);
    }),
    setInterval: ses.harden(
      (...[handler, interval]: Parameters<typeof setInterval>) => {
        const wrappedHandler = wrapHandler(
          typeof handler === 'function' ? handler : () => {},
        );
        const intervalId = setInterval(wrappedHandler, interval);

        plugin.intervals.add(intervalId);

        return ses.safeReturn(intervalId);
      },
    ) as typeof setInterval,
    clearInterval: ses.harden((id: ReturnType<typeof setInterval>) => {
      clearInterval(id);

      plugin.intervals.delete(id);
    }),

    /**
     * GLOBAL FUNCTIONS ACCESIBLE TO PLUGINS
     **/
    isFinite: ses.harden(isFinite),
    isNaN: ses.harden(isNaN),
    parseFloat: ses.harden(parseFloat),
    parseInt: ses.harden(parseInt),
    decodeURI: ses.harden(decodeURI),
    decodeURIComponent: ses.harden(decodeURIComponent),
    encodeURI: ses.harden(encodeURI),
    encodeURIComponent: ses.harden(encodeURIComponent),
    Object: ses.harden(Object),
    Boolean: ses.harden(Boolean),
    Symbol: ses.harden(Symbol),
    Number: ses.harden(Number),
    BigInt: ses.harden(BigInt),
    Math: ses.harden(Math),
    Date: ses.harden(Date),
    String: ses.harden(String),
    RegExp: ses.harden(RegExp),
    Array: ses.harden(Array),
    Int8Array: ses.harden(Int8Array),
    Uint8Array: ses.harden(Uint8Array),
    Uint8ClampedArray: ses.harden(Uint8ClampedArray),
    Int16Array: ses.harden(Int16Array),
    Uint16Array: ses.harden(Uint16Array),
    Int32Array: ses.harden(Int32Array),
    Uint32Array: ses.harden(Uint32Array),
    BigInt64Array: ses.harden(BigInt64Array),
    BigUint64Array: ses.harden(BigUint64Array),
    Float32Array: ses.harden(Float32Array),
    Float64Array: ses.harden(Float64Array),
    Map: ses.harden(Map),
    Set: ses.harden(Set),
    WeakMap: ses.harden(WeakMap),
    WeakSet: ses.harden(WeakSet),
    ArrayBuffer: ses.harden(ArrayBuffer),
    DataView: ses.harden(DataView),
    Atomics: ses.harden(Atomics),
    JSON: ses.harden(JSON),
    Promise: ses.harden(Promise),
    Proxy: ses.harden(Proxy),
    Intl: ses.harden(Intl),

    // Window properties
    console: ses.harden(window.console),
    devicePixelRatio: window.devicePixelRatio,
    atob: ses.harden(window.atob.bind(null)),
    btoa: ses.harden(window.btoa.bind(null)),
    structuredClone: ses.harden(window.structuredClone),
  };

  if (apiExtensions) {
    publicPluginApi = Object.assign(publicPluginApi, apiExtensions);
  }

  const compartment = ses.createCompartment(publicPluginApi);

  return {
    evaluate: () => {
      compartment.evaluate(plugin.code);
    },
    cleanGlobalThis: () => {
      Object.keys(publicPluginApi).forEach((key) => {
        delete compartment.globalThis[key];
      });
    },
    compartment,
  };
}

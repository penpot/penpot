// Minimal RPC client against the admin API.
//
// The API root is derived from this module's own URL: the panel is
// served at `<root>/admin/js/api.js` and the API lives at
// `<root>/api/...`, where `<root>` is empty on a root deployment or
// the subpath otherwise. No absolute paths, so subpath deployments
// keep working.
//
// Sessions travel in the cookie (`credentials: "same-origin"`), so there
// is no token handling here. 401/403 responses are propagated as thrown
// errors with a `status` field so callers can tell "no session" and
// "no permission" apart.

const API_ROOT = new URL("../../api/admin/methods/", import.meta.url);
const MAIN_API_ROOT = new URL("../../api/main/methods/", import.meta.url);

async function call(root, command, params = {}) {
  const response = await fetch(new URL(command, root), {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify(params),
  });

  if (response.status === 204) {
    return null;
  }

  if (!response.ok) {
    const error = new Error("rpc failed: " + command);
    error.status = response.status;
    try {
      error.data = await response.json();
    } catch {
      error.data = null;
    }
    throw error;
  }

  return response.json();
}

export async function rpc(command, params = {}) {
  return call(API_ROOT, command, params);
}

export async function rpcMain(command, params = {}) {
  return call(MAIN_API_ROOT, command, params);
}

// Human-readable detail from a failed RPC call, or null when the
// server gave nothing usable. Lets the panel repeat the backend
// reason (e.g. why a delete was refused) instead of a generic
// message. Every string is painted as text by the caller.
export function errorHint(err) {
  const hint = err?.data?.["~:hint"];
  return typeof hint === "string" && hint.length > 0 ? hint : null;
}

// Binary transfer calls go through the RPC methods endpoint directly
// (a download and a multipart upload do not fit the JSON rpc()
// helper, which always parses the answer as JSON).
export function methodUrl(name) {
  return new URL("../../api/admin/methods/" + name, import.meta.url);
}

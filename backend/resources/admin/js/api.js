// Minimal RPC client against the main API.
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

const API_ROOT = new URL("../../api/main/methods/", import.meta.url);

export async function rpc(command, params = {}) {
  const response = await fetch(new URL(command, API_ROOT), {
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

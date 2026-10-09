// Session gate against the current contract: the product
// `get-profile` (public, so anonymous callers get an answer too)
// carries `:is-superuser` when there is a session. It lives on the
// main API, hence `rpcMain`. JSON responses use camelCase, hence
// `isSuperuser`. `:is-admin` is team membership and means nothing
// here.

import { rpcMain } from "./api.js";

const ANONYMOUS_ID = "00000000-0000-0000-0000-000000000000";

// Login hop for a dead session (deleted profile, expired cookie):
// the product login lives at the deployment root (`<root>/`), next
// to the panel mount (`<root>/admin/`). It anchors on this module's
// own url — panel scripts always live at `<root>/admin/js/`, so two
// levels up is `<root>/` in every deployment (root or subpath) and
// from any page depth. Returns path plus query (a bare pathname
// would drop the screen). `moduleUrl` defaults to this file; tests
// pass their own url.
export function loginUrl(moduleUrl = import.meta.url) {
  const url = new URL("../../?screen=auth-login", moduleUrl);
  return url.pathname + url.search;
}

export async function checkSuperuserSession() {
  let profile;
  try {
    profile = await rpcMain("get-profile", {});
  } catch (err) {
    if (err.status === 401) {
      return "anonymous";
    }
    throw err;
  }

  if (!profile || !profile.id || profile.id === ANONYMOUS_ID) {
    return "anonymous";
  }
  return profile.isSuperuser === true ? "superuser" : "denied";
}

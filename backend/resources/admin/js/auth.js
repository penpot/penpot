// Session gate against the current contract: the product
// `get-profile` (public, so anonymous callers get an answer too)
// carries `:is-superuser` when there is a session. It lives on the
// main API, hence `rpcMain`. JSON responses use camelCase, hence
// `isSuperuser`. `:is-admin` is team membership and means nothing
// here.

import { rpcMain } from "./api.js";

const ANONYMOUS_ID = "00000000-0000-0000-0000-000000000000";

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

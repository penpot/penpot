// Session gate against the current contract: `get-profile` (public,
// so anonymous callers get an answer too) carries `:is-superuser`
// when there is a session. JSON responses use camelCase, hence
// `isSuperuser`. `:is-admin` is team membership and means nothing
// here.

import { rpc } from "./api.js";

const ANONYMOUS_ID = "00000000-0000-0000-0000-000000000000";

export async function checkSuperuserSession() {
  let profile;
  try {
    profile = await rpc("get-profile", {});
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

// Bootstrap: paint a loading state, resolve the session, and show the
// outcome. Task 4 replaces the status text with the real views
// (dashboard for superusers, access-denied otherwise).

import { checkSuperuserSession } from "./auth.js";
import { register, start } from "./router.js";

function loadingView(root) {
  root.textContent = "Loading admin…";
}

function statusView(status) {
  return (root) => {
    root.textContent = "Admin — session: " + status;
  };
}

register("/", loadingView);
register("*", loadingView);

async function boot() {
  start();
  let status;
  try {
    status = await checkSuperuserSession();
  } catch {
    status = "unreachable";
  }
  const root = document.getElementById("app");
  root.replaceChildren();
  statusView(status)(root);
}

document.addEventListener("DOMContentLoaded", boot);

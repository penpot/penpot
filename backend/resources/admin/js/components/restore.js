// Shared restore UI for a deleted object.
//
// command: "restore-file" and friends; id: the object id.
// label: "this file" for messages; `scope` names what else comes
// back with it ("its project and team", "everything under it").
// `warn`: optional extra warning appended to the confirm.
// onRestored(): what the page does after success (usually
// reloading). Restores are always recursive now: there is no
// single-level restore. The button asks for confirmation first.
// Every string is painted as text.

import { rpc } from "../api.js";
import { showToast } from "./toast.js";

export function deletedNotice(text) {
  const notice = document.createElement("p");
  notice.className = "admin-count";
  notice.textContent = text;
  return notice;
}

// Restore action for the top bar of a deleted object's detail
// page. Carries a tooltip; asks for confirmation first (naming
// what comes back). Returns a cleanup function removing
// everything added.
export function paintRestoreActions(bar, {
  command,
  id,
  label,
  name,
  scope,
  warn,
  onRestored,
}) {
  const message = `Restore ${name} ${scope}?` + (warn ? ` ${warn}` : "");

  const restore = document.createElement("button");
  restore.className = "admin-button admin-button-danger";
  restore.textContent = "Restore";
  restore.title = `Restore ${label} ${scope}.`;
  restore.addEventListener("click", async () => {
    if (!window.confirm(message)) {
      return;
    }
    restore.disabled = true;
    try {
      await rpc(command, { id });
      showToast(`Restored ${label} ${scope}.`);
      onRestored();
    } catch {
      showToast("Could not restore.", "error");
      restore.disabled = false;
    }
  });
  bar.appendChild(restore);

  return () => {
    restore.remove();
  };
}

export function restoreBlock({ command, id, label, scope, warn, onRestored }) {
  const section = document.createElement("section");
  section.className = "admin-block";
  const heading = document.createElement("h2");
  heading.textContent = "Restore";
  section.appendChild(heading);

  const hint = document.createElement("p");
  hint.className = "admin-count";
  hint.textContent = `Restore brings back ${label} ${scope}.`;
  section.appendChild(hint);

  if (warn) {
    const warnEl = document.createElement("p");
    warnEl.className = "admin-count";
    warnEl.textContent = warn;
    section.appendChild(warnEl);
  }

  const buttons = document.createElement("div");
  buttons.className = "admin-confirm";

  const restore = document.createElement("button");
  restore.className = "admin-button admin-button-danger";
  restore.textContent = "Restore";
  const message = `Restore ${label} ${scope}?` + (warn ? ` ${warn}` : "");
  restore.title = `Restore ${label} ${scope}.` + (warn ? ` ${warn}` : "");
  buttons.appendChild(restore);
  section.appendChild(buttons);

  const result = document.createElement("div");
  result.className = "admin-result";
  section.appendChild(result);

  restore.addEventListener("click", async () => {
    if (!window.confirm(message)) {
      return;
    }
    restore.disabled = true;
    result.textContent = "Restoring…";
    try {
      await rpc(command, { id });
      showToast(`Restored ${label} ${scope}.`);
      onRestored();
    } catch {
      result.textContent = "Could not restore.";
      showToast("Could not restore.", "error");
      restore.disabled = false;
    }
  });

  return section;
}

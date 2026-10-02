// Shared restore UI for a deleted object.
//
// command: "restore-file" and friends; id: the object id.
// label: "this file" for messages; confirmName: the object name
// the operator must type to confirm a recursive restore.
// warn: optional extra warning for the recursive case.
// onRestored(recursive): what the page does after success
// (usually reloading, or pointing at a still-deleted parent).
// Every string is painted as text.

import { rpc } from "../api.js";
import { showToast } from "./toast.js";

export function deletedNotice(text) {
  const notice = document.createElement("p");
  notice.className = "admin-count";
  notice.textContent = text;
  return notice;
}

export function restoreBlock({ command, id, label, confirmName, warn, onRestored }) {
  const section = document.createElement("section");
  section.className = "admin-block";
  const heading = document.createElement("h2");
  heading.textContent = "Restore";
  section.appendChild(heading);

  const hint = document.createElement("p");
  hint.className = "admin-count";
  hint.textContent =
    `Restore brings back ${label}. ` +
    "The recursive restore brings back everything under it as well.";
  section.appendChild(hint);

  if (warn) {
    const warnEl = document.createElement("p");
    warnEl.className = "admin-count";
    warnEl.textContent = warn;
    section.appendChild(warnEl);
  }

  const buttons = document.createElement("div");
  buttons.className = "admin-confirm";

  const simple = document.createElement("button");
  simple.className = "admin-button";
  simple.textContent = "Restore";
  buttons.appendChild(simple);

  const recursive = document.createElement("button");
  recursive.className = "admin-button admin-button-danger";
  recursive.textContent = "Restore recursive";
  buttons.appendChild(recursive);
  section.appendChild(buttons);

  const confirmWrap = document.createElement("div");
  confirmWrap.className = "admin-confirm";
  confirmWrap.hidden = true;
  const confirmText = document.createElement("p");
  confirmText.textContent = `Type "${confirmName}" to confirm the recursive restore.`;
  confirmWrap.appendChild(confirmText);
  const input = document.createElement("input");
  input.className = "admin-input";
  input.type = "text";
  input.placeholder = confirmName;
  input.setAttribute("aria-label", "Type the name to confirm recursive restore");
  confirmWrap.appendChild(input);
  const confirm = document.createElement("button");
  confirm.className = "admin-button admin-button-danger";
  confirm.textContent = "Restore recursive";
  confirm.disabled = true;
  confirmWrap.appendChild(confirm);
  section.appendChild(confirmWrap);

  const result = document.createElement("div");
  result.className = "admin-result";
  section.appendChild(result);

  simple.addEventListener("click", async () => {
    if (!window.confirm(`Restore ${label} (only it)?`)) {
      return;
    }
    simple.disabled = true;
    try {
      await rpc(command, { id });
      showToast("Restored.");
      onRestored(false);
    } catch {
      showToast("Could not restore.", "error");
      simple.disabled = false;
    }
  });

  recursive.addEventListener("click", () => {
    confirmWrap.hidden = false;
    input.focus();
  });
  input.addEventListener("input", () => {
    confirm.disabled = input.value.trim() !== confirmName;
  });
  confirm.addEventListener("click", async () => {
    confirm.disabled = true;
    result.textContent = "Restoring…";
    try {
      await rpc(command, { id, recursive: true });
      showToast("Restored with everything under it.");
      onRestored(true);
    } catch {
      result.textContent = "Could not restore.";
      showToast("Could not restore.", "error");
      confirm.disabled = false;
    }
  });

  return section;
}

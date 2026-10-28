// Shared delete UI for a live object.
//
// command: "delete-team" and friends; id: the object id.
// label: "this team" for messages; kind: "Team" for toasts;
// name: the object name for the confirm.
// onDeleted(): what the page does after success (usually reloading,
// which then paints the restore actions).
// Asks for confirmation first. Every string is painted as text.

import { rpc, errorHint } from "../api.js";
import { showToast } from "./toast.js";

// Delete action for the bar of a live object's detail page.
// Carries a tooltip; asks for confirmation first. Returns a
// cleanup function removing everything added.
export function paintDeleteAction(bar, {
  command,
  id,
  label,
  name,
  kind,
  onDeleted,
}) {
  const del = document.createElement("button");
  del.className = "admin-button admin-button-danger";
  del.textContent = "Delete";
  del.title = `Delete ${label}. It can be restored afterwards.`;
  del.addEventListener("click", async () => {
    if (!window.confirm(`Delete ${name}? It can be restored afterwards.`)) {
      return;
    }
    del.disabled = true;
    try {
      await rpc(command, { id });
      showToast(`${kind} deleted.`);
      onDeleted();
    } catch (err) {
      showToast(errorHint(err) ?? `Could not delete ${label}.`, "error");
      del.disabled = false;
    }
  });
  bar.appendChild(del);

  return () => {
    del.remove();
  };
}

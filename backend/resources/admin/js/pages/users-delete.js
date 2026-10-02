// Bulk delete by email: paste a list, preview the count, confirm
// by typing the number, and run delete-profiles. The answer
// paints deleted, not-found, and skipped-self groups separately.
// Everything is painted as text.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";

const MAX_EMAILS = 100;

function parseEmails(raw) {
  return raw
    .split(/[\n,]+/)
    .map((email) => email.trim())
    .filter((email) => email !== "");
}

export function usersDeletePage(root, { onNavigate }) {
  root.appendChild(renderHeader("Delete users by email"));

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate("?screen=users"));
  root.appendChild(back);

  const hint = document.createElement("p");
  hint.className = "admin-count";
  hint.textContent =
    `Paste up to ${MAX_EMAILS} emails, one per line or comma separated. ` +
    "Matches are marked for deletion at once; unknown emails are reported, not deleted.";
  root.appendChild(hint);

  const area = document.createElement("textarea");
  area.className = "admin-textarea";
  area.placeholder = "user1@example.com\nuser2@example.com";
  area.setAttribute("aria-label", "Emails to delete");
  root.appendChild(area);

  const countLine = document.createElement("p");
  countLine.className = "admin-count";
  countLine.textContent = "Paste at least one email.";
  root.appendChild(countLine);

  const confirmWrap = document.createElement("div");
  confirmWrap.className = "admin-confirm";

  const input = document.createElement("input");
  input.className = "admin-input";
  input.type = "text";
  input.inputMode = "numeric";
  input.setAttribute("aria-label", "Type the count to confirm deletion");
  confirmWrap.appendChild(input);

  const confirm = document.createElement("button");
  confirm.className = "admin-button admin-button-danger";
  confirm.textContent = "Delete";
  confirm.disabled = true;
  confirmWrap.appendChild(confirm);
  root.appendChild(confirmWrap);

  const body = document.createElement("div");
  root.appendChild(body);

  function emailCount() {
    return parseEmails(area.value).length;
  }

  function refresh() {
    const count = emailCount();
    confirm.textContent = count > 0 ? `Delete ${count}` : "Delete";
    if (count === 0) {
      countLine.textContent = "Paste at least one email.";
      confirm.disabled = true;
      return;
    }
    if (count > MAX_EMAILS) {
      countLine.textContent = `Too many emails: ${count} (max ${MAX_EMAILS}).`;
      confirm.disabled = true;
      return;
    }
    countLine.textContent =
      `${count} email` + (count === 1 ? "" : "s") +
      ` — type ${count} above to confirm. This cannot be undone.`;
    confirm.disabled = input.value.trim() !== String(count);
  }

  area.addEventListener("input", refresh);
  input.addEventListener("input", refresh);

  confirm.addEventListener("click", async () => {
    const emails = parseEmails(area.value);
    confirm.disabled = true;
    try {
      const data = await rpc("delete-profiles", { emails });
      body.replaceChildren();
      body.appendChild(resultBlock(data));
    } catch {
      showToast("Could not delete the users.", "error");
      refresh();
    }
  });

  refresh();

  function resultBlock(data) {
    const wrap = document.createElement("div");

    const summary = document.createElement("p");
    summary.className = "admin-count";
    summary.textContent =
      `Deleted ${data.deleted.length} of ${data.total}. ` +
      `${data.notFound.length} not found, ${data.skippedSelf.length} skipped (own account).`;
    wrap.appendChild(summary);

    const groups = [
      ["Deleted", data.deleted],
      ["Not found", data.notFound],
      ["Skipped (own account)", data.skippedSelf],
    ];
    for (const [title, items] of groups) {
      if (items.length === 0) {
        continue;
      }
      const section = document.createElement("section");
      section.className = "admin-block";
      const heading = document.createElement("h2");
      heading.textContent = `${title} (${items.length})`;
      section.appendChild(heading);
      const list = document.createElement("ul");
      list.className = "admin-list";
      for (const item of items) {
        const entry = document.createElement("li");
        entry.textContent = String(item);
        list.appendChild(entry);
      }
      section.appendChild(list);
      wrap.appendChild(section);
    }

    const done = document.createElement("button");
    done.className = "admin-button admin-button-ghost";
    done.textContent = "Back to list";
    done.addEventListener("click", () => onNavigate("?screen=users"));
    wrap.appendChild(done);

    return wrap;
  }
}

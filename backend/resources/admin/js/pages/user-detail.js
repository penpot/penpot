// User detail: metadata, teams, and admin actions. The email is
// the page title. Destructive actions confirm inline by typing
// the email; everything is painted as text. An unknown id shows
// "not found" instead of failing.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";
import { statusCell, statusOf } from "./users.js";

const ROWS = [
  ["id", "Id"],
  ["fullname", "Name"],
  ["createdAt", "Created"],
  ["deletedAt", "Deleted"],
  ["authBackend", "Auth backend"],
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

function roleOf(membership) {
  if (membership.isOwner) {
    return "owner";
  }
  if (membership.isAdmin) {
    return "admin";
  }
  return "member";
}

export function userDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("User");
  root.appendChild(header);

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate("?screen=users"));
  root.appendChild(back);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  load();

  async function load() {
    let data;
    try {
      data = await rpc("get-profile", { id });
    } catch (err) {
      body.replaceChildren();
      if (err.status === 404) {
        body.textContent = "User not found.";
      } else {
        body.textContent = "Could not load the user.";
        showToast("Could not load the user.", "error");
      }
      return;
    }

    header.querySelector("h1").textContent = data.email;

    const list = document.createElement("dl");
    list.className = "admin-detail";
    for (const [key, label] of ROWS) {
      if (data[key] === null || data[key] === undefined || data[key] === "") {
        continue;
      }
      const term = document.createElement("dt");
      term.textContent = label;
      const desc = document.createElement("dd");
      desc.textContent = key === "createdAt" || key === "deletedAt"
        ? formatDate(data[key])
        : String(data[key]);
      list.appendChild(term);
      list.appendChild(desc);
    }
    const statusTerm = document.createElement("dt");
    statusTerm.textContent = "Status";
    const statusDesc = document.createElement("dd");
    statusDesc.appendChild(statusCell(statusOf(data)));
    list.appendChild(statusTerm);
    list.appendChild(statusDesc);
    body.replaceChildren();
    body.appendChild(list);

    body.appendChild(teamBlock("Owned teams", data.ownedTeams ?? [], (team) => {
      const item = document.createElement("li");
      item.textContent = `${team.name} — ${team.members} member` +
        (team.members === 1 ? "" : "s");
      return item;
    }));
    body.appendChild(teamBlock("Member of", data.memberTeams ?? [], (team) => {
      const item = document.createElement("li");
      item.textContent = `${team.name} — ${roleOf(team)}`;
      return item;
    }));

    if (data.deletedAt) {
      const notice = document.createElement("p");
      notice.className = "admin-count";
      notice.textContent =
        `This user was marked for deletion on ${formatDate(data.deletedAt)}. ` +
        "No actions are available.";
      body.appendChild(notice);
      return;
    }

    body.appendChild(actionsBar(data));
  }

  function teamBlock(title, teams, renderItem) {
    const section = document.createElement("section");
    section.className = "admin-block";
    const heading = document.createElement("h2");
    heading.textContent = title;
    section.appendChild(heading);
    if (teams.length === 0) {
      const empty = document.createElement("p");
      empty.className = "admin-count";
      empty.textContent = "None.";
      section.appendChild(empty);
      return section;
    }
    const items = document.createElement("ul");
    items.className = "admin-list";
    for (const team of teams) {
      items.appendChild(renderItem(team));
    }
    section.appendChild(items);
    return section;
  }

  function actionsBar(data) {
    const bar = document.createElement("div");
    bar.className = "admin-actions";

    const toggle = document.createElement("button");
    toggle.className = "admin-button";
    toggle.textContent = data.isBlocked ? "Unblock" : "Block";
    toggle.addEventListener("click", async () => {
      toggle.disabled = true;
      try {
        await rpc(data.isBlocked ? "unblock-profile" : "block-profile",
          { id: data.id });
        showToast(data.isBlocked ? "User unblocked." : "User blocked.");
        load();
      } catch {
        showToast("Could not change the block state.", "error");
        toggle.disabled = false;
      }
    });
    bar.appendChild(toggle);

    const resend = document.createElement("button");
    resend.className = "admin-button admin-button-ghost";
    resend.textContent = "Resend verification";
    resend.addEventListener("click", async () => {
      resend.disabled = true;
      try {
        await rpc("resend-verification", { id: data.id });
        showToast("Verification email scheduled.");
      } catch {
        showToast("Could not schedule the verification email.", "error");
      } finally {
        resend.disabled = false;
      }
    });
    bar.appendChild(resend);

    const remove = document.createElement("button");
    remove.className = "admin-button admin-button-danger";
    remove.textContent = "Delete…";
    remove.addEventListener("click", () => {
      bar.replaceChildren();
      bar.appendChild(deleteConfirm(data, onNavigate));
    });
    bar.appendChild(remove);

    return bar;
  }

  function deleteConfirm(data, onNavigate) {
    const wrap = document.createElement("div");
    wrap.className = "admin-confirm";

    const label = document.createElement("p");
    label.textContent = `Type ${data.email} to delete this user. This cannot be undone.`;
    wrap.appendChild(label);

    const input = document.createElement("input");
    input.className = "admin-input";
    input.type = "text";
    input.setAttribute("aria-label", "Type the email to confirm deletion");
    wrap.appendChild(input);

    const confirm = document.createElement("button");
    confirm.className = "admin-button admin-button-danger";
    confirm.textContent = "Delete user";
    confirm.disabled = true;
    input.addEventListener("input", () => {
      confirm.disabled = input.value.trim() !== data.email;
    });
    confirm.addEventListener("click", async () => {
      confirm.disabled = true;
      try {
        await rpc("delete-profiles", { emails: [data.email] });
        showToast("User deleted.");
        onNavigate("?screen=users");
      } catch {
        showToast("Could not delete the user.", "error");
        confirm.disabled = false;
      }
    });
    wrap.appendChild(confirm);

    const cancel = document.createElement("button");
    cancel.className = "admin-button admin-button-ghost";
    cancel.textContent = "Cancel";
    cancel.addEventListener("click", () => {
      body.replaceChildren();
      load();
    });
    wrap.appendChild(cancel);

    return wrap;
  }
}

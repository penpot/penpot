// User detail: metadata, teams, and admin actions in the top bar.
// The email is the page title. Dangerous actions ask for
// confirmation first; everything is painted as text. An unknown id
// shows "not found" instead of failing.

import { rpc } from "../api.js";
import { renderHeader, paintDetailTitle } from "../components/header.js";
import { deletedNotice, paintRestoreActions } from "../components/restore.js";
import { showToast } from "../components/toast.js";
import { statusCell, statusOf } from "../components/badges.js";
import { formatDate } from "../components/date.js";
import { backUrl } from "../url.js";

const ROWS = [
  ["id", "Id"],
  ["fullname", "Name"],
  ["createdAt", "Created"],
  ["deletedAt", "Deleted"],
  ["authBackend", "Auth backend"],
];

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

  const bar = document.createElement("div");
  bar.className = "admin-actions";

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate(backUrl("?screen=users")));
  bar.appendChild(back);
  root.appendChild(bar);

  let clearBarActions = null;

  const clearActions = () => {
    if (clearBarActions) {
      clearBarActions();
      clearBarActions = null;
    }
  };

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
      clearActions();
      if (err.status === 404) {
        body.textContent = "User not found.";
      } else {
        body.textContent = "Could not load the user.";
        showToast("Could not load the user.", "error");
      }
      return;
    }

    paintDetailTitle(header, {
      section: "User",
      query: backUrl("?screen=users"),
      name: data.email,
      onNavigate,
    });

    // Fresh bar for fresh data: actions from a previous state
    // (e.g. Restore after the user came back) must not linger.
    clearActions();

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
      const link = document.createElement("a");
      link.textContent = team.name;
      link.href = "?screen=team&id=" + encodeURIComponent(team.id);
      link.addEventListener("click", (event) => {
        event.preventDefault();
        onNavigate("?screen=team&id=" + encodeURIComponent(team.id));
      });
      item.appendChild(link);
      item.appendChild(document.createTextNode(
        ` — ${team.members} member` + (team.members === 1 ? "" : "s")));
      if (team.deletedAt) {
        item.appendChild(document.createTextNode(" "));
        item.appendChild(statusCell("deleted"));
      }
      return item;
    }));
    body.appendChild(teamBlock("Member of", data.memberTeams ?? [], (team) => {
      const item = document.createElement("li");
      const link = document.createElement("a");
      link.textContent = team.name;
      link.href = "?screen=team&id=" + encodeURIComponent(team.id);
      link.addEventListener("click", (event) => {
        event.preventDefault();
        onNavigate("?screen=team&id=" + encodeURIComponent(team.id));
      });
      item.appendChild(link);
      item.appendChild(document.createTextNode(` — ${roleOf(team)}`));
      if (team.deletedAt) {
        item.appendChild(document.createTextNode(" "));
        item.appendChild(statusCell("deleted"));
      }
      return item;
    }));

    if (data.deletedAt) {
      const notice = document.createElement("p");
      notice.className = "admin-count";
      notice.textContent =
        `This user was marked for deletion on ${formatDate(data.deletedAt)}.`;
      body.appendChild(notice);
      clearBarActions = paintRestoreActions(bar, {
        command: "restore-profile",
        id,
        label: "this user",
        name: data.email,
        scope: "with the teams it owned and everything under them",
        warn: "Teams without any other live owner come back too, " +
          "because this user owns them again.",
        onRestored: () => load(),
      });
      return;
    }

    paintUserActions(data);
  }

  function paintUserActions(data) {
    const added = [];

    const toggle = document.createElement("button");
    toggle.className = "admin-button";
    toggle.textContent = data.isBlocked ? "Unblock" : "Block";
    toggle.title = data.isBlocked ? "Unblock this user." : "Block this user.";
    toggle.addEventListener("click", async () => {
      const verb = data.isBlocked ? "Unblock" : "Block";
      if (!window.confirm(`${verb} ${data.email}?`)) {
        return;
      }
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
    added.push(toggle);

    const resend = document.createElement("button");
    resend.className = "admin-button";
    resend.textContent = "Resend verification";
    resend.title = "Send the verification email again.";
    resend.addEventListener("click", async () => {
      if (!window.confirm(`Send a verification email to ${data.email}?`)) {
        return;
      }
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
    added.push(resend);

    const remove = document.createElement("button");
    remove.className = "admin-button admin-button-danger";
    remove.textContent = "Delete";
    remove.title = "Delete this user. It can be restored afterwards.";
    remove.addEventListener("click", async () => {
      if (!window.confirm(`Delete ${data.email}? It can be restored afterwards.`)) {
        return;
      }
      remove.disabled = true;
      try {
        const result = await rpc("delete-profiles", { emails: [data.email] });
        if ((result.skippedSelf ?? []).length > 0) {
          showToast("You cannot delete your own account.", "error");
        } else {
          showToast("User deleted.");
        }
        load();
      } catch {
        showToast("Could not delete the user.", "error");
        remove.disabled = false;
      }
    });
    bar.appendChild(remove);
    added.push(remove);

    clearBarActions = () => {
      for (const el of added) {
        el.remove();
      }
    };
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

}

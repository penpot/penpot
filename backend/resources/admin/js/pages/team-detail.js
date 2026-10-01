// Team detail: metadata, feature toggles, and members. The name
// is the page title. Every value is painted as text. Enabling a
// feature applies at once; disabling asks for confirmation first.
// An unknown id shows "not found" instead of failing.

import { rpc } from "../api.js";
import { formatDate } from "../components/date.js";
import { renderHeader, paintDetailTitle } from "../components/header.js";
import { deletedNotice, paintRestoreActions } from "../components/restore.js";
import { paintDeleteAction } from "../components/delete.js";
import { showToast } from "../components/toast.js";
import { defaultCell, statusCell, statusOf } from "../components/badges.js";
import { backUrl } from "../url.js";

// Mirror of `supported-features` in `common/src/app/common/features.cljc`.
// It only paints the toggles; the backend still validates every name.
const SUPPORTED_FEATURES = [
  "components/v2",
  "design-tokens/v1",
  "fdata/objects-map",
  "fdata/path-data",
  "fdata/pointer-map",
  "fdata/shape-data-type",
  "layout/grid",
  "plugins/runtime",
  "render-wasm/v1",
  "styles/v2",
  "text-editor/v2",
  "text-editor/v2-html-paste",
  "text-editor-wasm/v1",
  "tokens/numeric-input",
  "variants/v1",
];

const ROWS = [
  ["id", "Id"],
  ["createdAt", "Created"],
  ["deletedAt", "Deleted"],
  ["totalMembers", "Members"],
  ["totalProjects", "Projects"],
];

function roleOf(membership) {
  if (membership.isOwner) {
    return "owner";
  }
  if (membership.isAdmin) {
    return "admin";
  }
  if (membership.canEdit) {
    return "editor";
  }
  return "viewer";
}

const MEMBER_ROLES = ["owner", "admin", "editor", "viewer"];

export function teamDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("Team");
  root.appendChild(header);

  const bar = document.createElement("div");
  bar.className = "admin-actions";

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate(backUrl("?screen=teams")));
  bar.appendChild(back);

  const filesLink = document.createElement("button");
  filesLink.className = "admin-button admin-button-ghost";
  filesLink.textContent = "View files";
  filesLink.addEventListener("click", () =>
    onNavigate("?screen=files&teamId=" + encodeURIComponent(id)));
  bar.appendChild(filesLink);

  const projectsLink = document.createElement("button");
  projectsLink.className = "admin-button admin-button-ghost";
  projectsLink.textContent = "View projects";
  projectsLink.addEventListener("click", () =>
    onNavigate("?screen=projects&teamId=" + encodeURIComponent(id)));
  bar.appendChild(projectsLink);
  root.appendChild(bar);

  let clearRestoreActions = null;
  let clearDeleteAction = null;


  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  load();

  async function load() {
    let data;
    let members;
    try {
      [data, members] = await Promise.all([
        rpc("get-team", { id }),
        rpc("get-team-members", { teamId: id }),
      ]);
    } catch (err) {
      body.replaceChildren();
      if (clearRestoreActions) {
        clearRestoreActions();
        clearRestoreActions = null;
      }
      if (clearDeleteAction) {
        clearDeleteAction();
        clearDeleteAction = null;
      }
      if (err.status === 404) {
        body.textContent = "Team not found.";
      } else {
        body.textContent = "Could not load the team.";
        showToast("Could not load the team.", "error");
      }
      return;
    }

    paintDetailTitle(header, {
      section: "Team",
      query: backUrl("?screen=teams"),
      name: data.name,
      onNavigate,
    });

    const list = document.createElement("dl");
    list.className = "admin-detail";
    for (const [key, label] of ROWS) {
      if (data[key] === null || data[key] === undefined || data[key] === "") {
        continue;
      }
      const term = document.createElement("dt");
      term.textContent = label;
      const desc = document.createElement("dd");
      desc.textContent = key === "createdAt" || key === "deletedAt" ? formatDate(data[key]) : String(data[key]);
      list.appendChild(term);
      list.appendChild(desc);
    }
    if (data.isDefault) {
      const term = document.createElement("dt");
      term.textContent = "Default";
      const desc = document.createElement("dd");
      desc.appendChild(defaultCell("yes"));
      list.appendChild(term);
      list.appendChild(desc);
    }
    body.replaceChildren();

    if (data.deletedAt) {
      body.appendChild(deletedNotice("This team was marked for deletion."));
      if (clearDeleteAction) {
        clearDeleteAction();
        clearDeleteAction = null;
      }
      if (clearRestoreActions) {
        clearRestoreActions();
      }
      // NOTE: default teams come back with their owner profile, so
      // no Restore button is painted for them.
      if (data.isDefault) {
        clearRestoreActions = null;
        const hint = document.createElement("p");
        hint.className = "admin-count";
        hint.textContent =
          "This is a default team. Restore its owner profile to bring it back.";
        body.appendChild(hint);
      } else {
        clearRestoreActions = paintRestoreActions(bar, {
          command: "restore-team",
          id,
          label: "this team",
          name: data.name,
          scope: "with everything under it (projects, files)",
          onRestored: () => load(),
        });
      }
    } else {
      if (clearRestoreActions) {
        clearRestoreActions();
        clearRestoreActions = null;
      }
      if (clearDeleteAction) {
        clearDeleteAction();
      }
      // NOTE: default teams cannot be deleted (the backend refuses
      // them too), so no Delete button is painted for them.
      if (!data.isDefault) {
        clearDeleteAction = paintDeleteAction(bar, {
          command: "delete-team",
          id,
          label: "this team",
          name: data.name,
          kind: "Team",
          onDeleted: () => load(),
        });
      } else {
        clearDeleteAction = null;
      }
    }

    body.appendChild(list);

    body.appendChild(featuresBlock(data));
    body.appendChild(membersBlock(members ?? []));
  }

  function featuresBlock(data) {
    const section = document.createElement("section");
    section.className = "admin-block";
    const heading = document.createElement("h2");
    heading.textContent = "Features";
    section.appendChild(heading);

    const enabled = new Set(data.features ?? []);
    const items = document.createElement("ul");
    items.className = "admin-list";
    for (const feature of SUPPORTED_FEATURES) {
      const item = document.createElement("li");
      const label = document.createElement("label");
      const toggle = document.createElement("input");
      toggle.type = "checkbox";
      toggle.checked = enabled.has(feature);
      toggle.setAttribute("aria-label", `Toggle ${feature}`);
      toggle.addEventListener("change", async () => {
        toggle.disabled = true;
        const turnOn = toggle.checked;
        if (!turnOn && !window.confirm(
          `Turn off ${feature} for this team? Files may depend on it.`)) {
          toggle.checked = true;
          toggle.disabled = false;
          return;
        }
        try {
          await rpc(turnOn ? "enable-team-feature" : "disable-team-feature",
            { teamId: data.id, feature });
          showToast(turnOn ? `Enabled ${feature}.` : `Disabled ${feature}.`);
          load();
        } catch {
          showToast("Could not change the feature.", "error");
          toggle.checked = !turnOn;
          toggle.disabled = false;
        }
      });
      label.appendChild(toggle);
      label.append(` ${feature}`);
      item.appendChild(label);
      items.appendChild(item);
    }
    section.appendChild(items);

    const globalOnly = (data.effectiveFeatures ?? [])
      .filter((feature) => !enabled.has(feature));
    if (globalOnly.length > 0) {
      const note = document.createElement("p");
      note.className = "admin-count";
      note.textContent =
        "Also active for everyone (global switch, not per team): " +
        globalOnly.join(", ") + ".";
      section.appendChild(note);
    }
    return section;
  }

  function membersBlock(members) {
    const section = document.createElement("section");
    section.className = "admin-block";
    const heading = document.createElement("h2");
    heading.textContent = `Members (${members.length})`;
    section.appendChild(heading);
    if (members.length === 0) {
      const empty = document.createElement("p");
      empty.className = "admin-count";
      empty.textContent = "None.";
      section.appendChild(empty);
      return section;
    }
    const table = document.createElement("table");
    table.className = "admin-table";
    const head = document.createElement("thead");
    const headRow = document.createElement("tr");
    for (const label of ["Name", "Email", "Role", "Status"]) {
      const cell = document.createElement("th");
      cell.textContent = label;
      headRow.appendChild(cell);
    }
    head.appendChild(headRow);
    table.appendChild(head);
    const body = document.createElement("tbody");
    for (const member of members) {
      body.appendChild(memberRow(member));
    }
    table.appendChild(body);
    section.appendChild(table);
    return section;
  }

  function memberRow(member) {
    const row = document.createElement("tr");
    const name = document.createElement("td");
    name.textContent = member.fullname;
    row.appendChild(name);

    const email = document.createElement("td");
    email.textContent = member.email;
    row.appendChild(email);

    const roleCell = document.createElement("td");
    const select = document.createElement("select");
    select.className = "admin-select";
    select.setAttribute("aria-label", `Role of ${member.email}`);
    for (const role of MEMBER_ROLES) {
      const option = document.createElement("option");
      option.value = role;
      option.textContent = role;
      select.appendChild(option);
    }
    select.value = roleOf(member);
    if (member.isOwner) {
      // Ownership moves by promoting someone else, which demotes
      // the current owner; the backend rejects direct demotion.
      select.disabled = true;
      select.title = "Promote another member to transfer ownership.";
    }
    select.addEventListener("change", async () => {
      const previous = roleOf(member);
      const next = select.value;
      if ((previous === "owner" || next === "owner") && !window.confirm(
        `Make ${member.email} ${next}? The previous owner becomes admin.`)) {
        select.value = previous;
        return;
      }
      select.disabled = true;
      try {
        await rpc("update-team-member-role", { teamId: id, memberId: member.id, role: next });
        showToast(`${member.email} is now ${next}.`);
        load();
      } catch {
        showToast("Could not change the role.", "error");
        select.value = previous;
        select.disabled = false;
      }
    });
    roleCell.appendChild(select);
    row.appendChild(roleCell);

    const status = document.createElement("td");
    status.appendChild(statusCell(statusOf(member)));
    row.appendChild(status);
    return row;
  }
}

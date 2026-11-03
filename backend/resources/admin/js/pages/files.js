// Files list: id lookup with a deleted filter and pagination, plus
// import.
//
// Lookup is by exact id (primary key); substring search has no
// usable index at PRO scale. `teamId`/`projectId` are not offered
// as selectors here: they only arrive through the URL, from the
// "view files" links, and show as removable active filters. Every
// value is painted as text. JSON responses use camelCase
// (`nextSince`, …).

import { rpc, methodUrl } from "../api.js";
import { listPage } from "../components/list-page.js";
import { statusCell } from "../components/badges.js";
import { formatDate } from "../components/date.js";
import { showToast } from "../components/toast.js";

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name admin-cell-fill" },
  { key: "projectName", label: "Project", class: "admin-cell-project" },
  { key: "teamName", label: "Team", class: "admin-cell-team" },
  { key: "createdAt", label: "Created", class: "admin-cell-date" },
  { key: "modifiedAt", label: "Modified", class: "admin-cell-date" },
  { key: "deletedAt", label: "Deleted", class: "admin-cell-date" },
  { key: "status", label: "Status", class: "admin-cell-status" },
];

export function filesPage(root, { onNavigate }) {
  let importLabel = null;

  async function upload(picked, state, reload) {
    showToast("Importing " + picked.name + "…");
    const form = new FormData();
    form.append("file", picked, picked.name);
    try {
      const response = await fetch(methodUrl("import-files"), {
        method: "POST",
        credentials: "same-origin",
        body: form,
      });
      if (!response.ok) {
        throw new Error("import failed");
      }
      showToast("Imported " + picked.name + ".");
      state.nextSince = null;
      state.nextId = null;
      reload();
    } catch {
      showToast("Could not import " + picked.name + ".", "error");
    }
  }

  listPage(root, {
    title: "Files",
    noun: "files",
    itemNoun: "file",
    emptyText: "No files match the current filters.",
    columns: COLUMNS,
    rpcCommand: "get-files",
    loadErrorText: "Could not load files.",
    lookupPlaceholder: "Look up by id…",
    lookupAriaLabel: "Look up by file id",
    detailKind: "file",
    tableClass: "admin-table-auto",
    hideEmptyTable: true,
    extraQueryKeys: ["teamId", "projectId"],
    initialState: (fromUrl) => ({
      teamId: fromUrl.teamId ?? null,
      teamName: null,
      projectId: fromUrl.projectId ?? null,
      projectName: null,
    }),
    resetExtra: (state) => {
      state.teamId = null;
      state.teamName = null;
      state.projectId = null;
      state.projectName = null;
    },
    readRpcParams: (state) => ({
      ...(state.lookup ? { id: state.lookup } : {}),
      ...(state.teamId ? { teamId: state.teamId } : {}),
      ...(state.projectId ? { projectId: state.projectId } : {}),
    }),
    beforeLoad: async (state) => {
      if (state.teamId && state.teamName === null) {
        try {
          const team = await rpc("get-team", { id: state.teamId });
          state.teamName = team.name;
        } catch {
          state.teamName = null;
        }
      }
      if (state.projectId && state.projectName === null) {
        try {
          const project = await rpc("get-project", { id: state.projectId });
          state.projectName = project.name ?? null;
        } catch {
          state.projectName = null;
        }
      }
    },
    refreshHeader: ({ state, root: pageRoot, onNavigate: navigate }) => {
      const titleEl = pageRoot.querySelector("h1");
      titleEl.replaceChildren();
      const crumbs = [];
      if (state.teamId !== null) {
        crumbs.push({
          label: "Team: " + (state.teamName ?? state.teamId),
          query: "?screen=team&id=" + encodeURIComponent(state.teamId),
        });
      }
      if (state.projectId !== null) {
        crumbs.push({
          label: "Project: " + (state.projectName ?? state.projectId),
          query: "?screen=project&id=" + encodeURIComponent(state.projectId),
        });
      }
      crumbs.push({ label: "Files" });
      crumbs.forEach((crumb, index) => {
        if (index > 0) {
          titleEl.append(" > ");
        }
        if (crumb.query) {
          const crumbButton = document.createElement("button");
          crumbButton.className = "admin-crumb";
          crumbButton.textContent = crumb.label;
          crumbButton.setAttribute("aria-label", "Back to " + crumb.label);
          crumbButton.addEventListener("click", () => navigate(crumb.query));
          titleEl.appendChild(crumbButton);
        } else {
          titleEl.append(crumb.label);
        }
      });
    },
    decorateRow: (item) => ({
      ...item,
      createdAt: formatDate(item.createdAt),
      modifiedAt: formatDate(item.modifiedAt),
      deletedAt: item.deletedAt ? formatDate(item.deletedAt) : "—",
      status: item.deletedAt ? "deleted" : "active",
    }),
    patchRow: (rowEl, row, _item, { cellByKey }) => {
      cellByKey(rowEl, "status")?.replaceChildren(statusCell(row.status));
    },
    extraControls: (controls, { state, reload }) => {
      importLabel = document.createElement("label");
      importLabel.className = "admin-button";
      importLabel.textContent = "Import…";
      const importInput = document.createElement("input");
      importInput.type = "file";
      importInput.accept = ".penpot";
      importInput.hidden = true;
      importInput.setAttribute("aria-label", "Import a penpot file");
      importInput.addEventListener("change", () => {
        if (importInput.files.length > 0) {
          upload(importInput.files[0], state, reload);
          importInput.value = "";
        }
      });
      importLabel.appendChild(importInput);
      controls.appendChild(importLabel);
    },
    paintExtra: ({ state }) => {
      importLabel?.classList.toggle("admin-button-disabled", state.loading);
    },
    onNavigate,
  });
}

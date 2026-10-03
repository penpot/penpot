// Files list: searchable table with pagination, plus import.
//
// The search matches by name or by exact id (pasting the id from
// the editor URL jumps straight to the file). `teamId` is not
// offered as a selector here: it only arrives through the URL,
// from the "view files" link on a team page, and shows as a
// removable active filter. Every value is painted as text. JSON
// responses use camelCase (`nextSince`, …).

import { rpc, transferUrl } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { showToast } from "../components/toast.js";
import { readQuery, writeQuery, detailUrl } from "../url.js";
import { statusCell } from "./users.js";

const PAGE_SIZE = 25;

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name admin-cell-fill" },
  { key: "projectName", label: "Project", class: "admin-cell-project" },
  { key: "teamName", label: "Team", class: "admin-cell-team" },
  { key: "modifiedAt", label: "Modified", class: "admin-cell-date" },
  { key: "deletedAt", label: "Deleted", class: "admin-cell-date" },
  { key: "status", label: "Status", class: "admin-cell-status" },
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

export function filesPage(root, { onNavigate }) {
  const fromUrl = readQuery(["search", "teamId", "projectId"]);
  const state = {
    search: fromUrl.search ?? "",
    teamId: fromUrl.teamId ?? null,
    teamName: null,
    projectId: fromUrl.projectId ?? null,
    projectName: null,
    items: [],
    nextSince: null,
    nextId: null,
    loading: false,
    loadedOnce: false,
  };

  root.appendChild(renderHeader("Files"));
  const titleEl = root.querySelector("h1");

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const searchInput = document.createElement("input");
  searchInput.className = "admin-input";
  searchInput.type = "search";
  searchInput.placeholder = "Search by name or id…";
  searchInput.setAttribute("aria-label", "Search by file name or id");
  searchInput.value = state.search;
  searchInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      searchButton.click();
    }
  });
  controls.appendChild(searchInput);

  const searchButton = document.createElement("button");
  searchButton.className = "admin-button";
  searchButton.textContent = "Search";
  searchButton.addEventListener("click", () => {
    state.search = searchInput.value.trim();
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(searchButton);

  const clearButton = document.createElement("button");
  clearButton.className = "admin-button admin-button-ghost";
  clearButton.textContent = "Clear";
  clearButton.addEventListener("click", () => {
    searchInput.value = "";
    state.search = "";
    state.teamId = null;
    state.teamName = null;
    state.projectId = null;
    state.projectName = null;
    state.nextSince = null;
    state.nextId = null;
    load();
  });
  controls.appendChild(clearButton);

  const importLabel = document.createElement("label");
  importLabel.className = "admin-button";
  importLabel.textContent = "Import…";
  const importInput = document.createElement("input");
  importInput.type = "file";
  importInput.accept = ".penpot";
  importInput.hidden = true;
  importInput.setAttribute("aria-label", "Import a penpot file");
  importInput.addEventListener("change", () => {
    if (importInput.files.length > 0) {
      upload(importInput.files[0]);
      importInput.value = "";
    }
  });
  importLabel.appendChild(importInput);
  controls.appendChild(importLabel);
  root.appendChild(controls);

  const countLine = document.createElement("p");
  countLine.className = "admin-count admin-count-spaced";
  root.appendChild(countLine);

  const tableWrap = document.createElement("div");
  root.appendChild(tableWrap);

  const pager = document.createElement("div");
  pager.className = "admin-pager";
  const nextButton = document.createElement("button");
  nextButton.className = "admin-button";
  nextButton.textContent = "Next page";
  nextButton.addEventListener("click", () => load({ append: true }));
  pager.appendChild(nextButton);
  root.appendChild(pager);

  function refreshTitle() {
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
        crumbButton.addEventListener("click", () => onNavigate(crumb.query));
        titleEl.appendChild(crumbButton);
      } else {
        titleEl.append(crumb.label);
      }
    });
  }

  function paint() {
    tableWrap.replaceChildren();
    if (state.loading && !state.loadedOnce) {
      countLine.textContent = "Loading…";
    } else if (state.items.length === 0) {
      countLine.textContent = "No files match the current filters.";
    } else {
      countLine.textContent =
        `Showing ${state.items.length} file` + (state.items.length === 1 ? "" : "s") +
        (state.nextSince === null ? " (all)" : " — more available");
    }

    refreshTitle();

    if (state.items.length > 0) {
      const rows = state.items.map((item) => ({
        ...item,
        modifiedAt: formatDate(item.modifiedAt),
        deletedAt: item.deletedAt ? formatDate(item.deletedAt) : "—",
        status: item.deletedAt ? "deleted" : "active",
      }));
      const table = renderTable(COLUMNS, rows);
      // Pack the columns: name eats the slack, the rest shrink to
      // their content (see `.admin-table-auto` in admin.css).
      table.classList.add("admin-table-auto");
      const bodyRows = table.querySelector("tbody").rows;
      for (let i = 0; i < bodyRows.length; i++) {
        const row = bodyRows[i];
        row.cells[5].replaceChildren(statusCell(rows[i].status));
        row.addEventListener("click", () =>
          onNavigate(detailUrl("file", state.items[i].id)));
      }
      tableWrap.appendChild(table);
    }
    nextButton.disabled = state.loading || state.nextSince === null;
    searchButton.disabled = state.loading;
    clearButton.disabled = state.loading;
    importLabel.classList.toggle("admin-button-disabled", state.loading);
    pager.style.display =
      state.items.length === 0 || state.nextSince === null ? "none" : "";
  }

  async function load({ append = false } = {}) {
    state.loading = true;
    paint();
    writeQuery({ search: state.search, teamId: state.teamId, projectId: state.projectId });
    const params = { limit: PAGE_SIZE };
    if (state.search) {
      params.search = state.search;
    }
    if (state.teamId) {
      params.teamId = state.teamId;
    }
    if (state.projectId) {
      params.projectId = state.projectId;
    }
    if (append && state.nextSince) {
      params.since = state.nextSince;
      params.sinceId = state.nextId;
    }
    try {
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
          const found = await rpc("get-projects", { search: state.projectId });
          state.projectName = (found.items ?? []).find((item) => item.id === state.projectId)?.name ?? null;
        } catch {
          state.projectName = null;
        }
      }
      const data = await rpc("get-files", params);
      state.items = append ? state.items.concat(data.items) : data.items;
      state.nextSince = data.nextSince ?? null;
      state.nextId = data.nextId ?? null;
      state.loadedOnce = true;
    } catch {
      showToast("Could not load files.", "error");
    } finally {
      state.loading = false;
      paint();
    }
  }

  async function upload(picked) {
    showToast("Importing " + picked.name + "…");
    const form = new FormData();
    form.append("file", picked, picked.name);
    try {
      const response = await fetch(transferUrl("file-import"), {
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
      load();
    } catch {
      showToast("Could not import " + picked.name + ".", "error");
    }
  }

  paint();
  load();
}

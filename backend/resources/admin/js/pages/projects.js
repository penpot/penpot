// Projects list: id lookup with a deleted filter and pagination.
//
// Lookup is by exact id (primary key); substring search has no
// usable index at PRO scale. `teamId` is not offered as a
// selector here: it only arrives through the URL, from the "view
// projects" link on a team page, and shows as a removable active
// filter. Every value is painted as text. JSON responses use
// camelCase (`nextSince`, …).

import { rpc } from "../api.js";
import { listPage } from "../components/list-page.js";
import { statusCell } from "../components/badges.js";
import { formatDate } from "../components/date.js";

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name admin-cell-fill" },
  { key: "teamName", label: "Team", class: "admin-cell-team" },
  { key: "totalFiles", label: "Files", class: "admin-cell-count" },
  { key: "createdAt", label: "Created", class: "admin-cell-date" },
  { key: "modifiedAt", label: "Modified", class: "admin-cell-date" },
  { key: "deletedAt", label: "Deleted", class: "admin-cell-date" },
  { key: "status", label: "Status", class: "admin-cell-status" },
];

export function projectsPage(root, { onNavigate }) {
  listPage(root, {
    title: "Projects",
    noun: "projects",
    itemNoun: "project",
    emptyText: "No projects match the current filters.",
    columns: COLUMNS,
    rpcCommand: "get-projects",
    loadErrorText: "Could not load projects.",
    lookupPlaceholder: "Look up by id…",
    lookupAriaLabel: "Look up by project id",
    detailKind: "project",
    tableClass: "admin-table-auto",
    hideEmptyTable: true,
    extraQueryKeys: ["teamId"],
    initialState: (fromUrl) => ({
      teamId: fromUrl.teamId ?? null,
      teamName: null,
    }),
    resetExtra: (state) => {
      state.teamId = null;
      state.teamName = null;
    },
    readRpcParams: (state) => ({
      ...(state.lookup ? { id: state.lookup } : {}),
      ...(state.teamId ? { teamId: state.teamId } : {}),
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
    },
    refreshHeader: ({ state, root: pageRoot, onNavigate: navigate }) => {
      const titleEl = pageRoot.querySelector("h1");
      titleEl.replaceChildren();
      if (state.teamId !== null) {
        const crumbButton = document.createElement("button");
        crumbButton.className = "admin-crumb";
        crumbButton.textContent = "Team: " + (state.teamName ?? state.teamId);
        crumbButton.setAttribute(
          "aria-label", "Back to Team: " + (state.teamName ?? state.teamId));
        crumbButton.addEventListener("click", () =>
          navigate("?screen=team&id=" + encodeURIComponent(state.teamId)));
        titleEl.appendChild(crumbButton);
        titleEl.append(" > ");
      }
      titleEl.append("Projects");
    },
    decorateRow: (item) => ({
      ...item,
      totalFiles: String(item.totalFiles ?? 0),
      createdAt: formatDate(item.createdAt),
      modifiedAt: formatDate(item.modifiedAt),
      deletedAt: item.deletedAt ? formatDate(item.deletedAt) : "—",
      status: item.deletedAt ? "deleted" : "active",
    }),
    patchRow: (rowEl, row, _item, { cellByKey }) => {
      cellByKey(rowEl, "status")?.replaceChildren(statusCell(row.status));
    },
    onNavigate,
  });
}

// Users list: id/email lookup with a deleted filter and pagination.
// Lookup is by exact id or exact email (both indexed); substring
// search has no usable index at PRO scale. Every value is painted
// as text; status badges use a fixed class map, never the raw
// value. JSON responses use camelCase (`nextSince`, …).

import { withFrom } from "../url.js";
import { listPage } from "../components/list-page.js";
import { statusCell, statusOf } from "../components/badges.js";
import { formatDate } from "../components/date.js";

const COLUMNS = [
  { key: "fullname", label: "Name", class: "admin-cell-name" },
  { key: "email", label: "Email", class: "admin-cell-email" },
  { key: "createdAt", label: "Created", class: "admin-cell-date" },
  { key: "modifiedAt", label: "Modified", class: "admin-cell-date" },
  { key: "deletedAt", label: "Deleted", class: "admin-cell-date" },
  { key: "status", label: "Status", class: "admin-cell-status" },
];

export function usersPage(root, { onNavigate }) {
  listPage(root, {
    title: "Users",
    noun: "users",
    itemNoun: "user",
    emptyText: "No users match the current filters.",
    columns: COLUMNS,
    rpcCommand: "get-profiles",
    loadErrorText: "Could not load users.",
    lookupPlaceholder: "Look up by id or exact email…",
    lookupAriaLabel: "Look up by id or exact email",
    detailKind: "user",
    readRpcParams: (state) => {
      if (!state.lookup) {
        return {};
      }
      // Exact email (unique index) when it looks like one,
      // otherwise an exact id (primary key).
      if (state.lookup.includes("@")) {
        return { email: state.lookup };
      }
      return { id: state.lookup };
    },
    decorateRow: (item) => ({
      ...item,
      createdAt: formatDate(item.createdAt),
      modifiedAt: formatDate(item.modifiedAt),
      deletedAt: item.deletedAt ? formatDate(item.deletedAt) : "—",
      status: statusOf(item),
    }),
    patchRow: (rowEl, row, _item, { cellByKey }) => {
      cellByKey(rowEl, "status")?.replaceChildren(statusCell(row.status));
    },
    extraControls: (controls, { onNavigate: navigate }) => {
      const bulkButton = document.createElement("button");
      bulkButton.className = "admin-button admin-button-ghost";
      bulkButton.textContent = "Delete by email…";
      bulkButton.addEventListener("click", () => navigate(withFrom("?screen=users-delete")));
      controls.appendChild(bulkButton);
    },
    onNavigate,
  });
}

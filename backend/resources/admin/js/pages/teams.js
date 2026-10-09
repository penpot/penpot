// Teams list: id lookup with a deleted filter and pagination.
// Lookup is by exact id (primary key); substring search has no
// usable index at PRO scale. Every value is painted as text. JSON
// responses use camelCase (`nextSince`, …).

import { listPage } from "../components/list-page.js";
import { defaultCell, statusCell } from "../components/badges.js";
import { formatDate } from "../components/date.js";

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name" },
  { key: "owner", label: "Owner", class: "admin-cell-email admin-cell-left" },
  { key: "totalMembers", label: "Members", class: "admin-cell-count" },
  { key: "createdAt", label: "Created", class: "admin-cell-date" },
  { key: "modifiedAt", label: "Modified", class: "admin-cell-date" },
  { key: "deletedAt", label: "Deleted", class: "admin-cell-date" },
  { key: "isDefault", label: "Default", class: "admin-cell-status admin-cell-center" },
  { key: "status", label: "Status", class: "admin-cell-status" },
];

export function teamsPage(root, { onNavigate }) {
  listPage(root, {
    title: "Teams",
    noun: "teams",
    itemNoun: "team",
    emptyText: "No teams match the current filters.",
    columns: COLUMNS,
    rpcCommand: "get-teams",
    loadErrorText: "Could not load teams.",
    lookupPlaceholder: "Look up by id…",
    lookupAriaLabel: "Look up by team id",
    detailKind: "team",
    readRpcParams: (state) => (state.lookup ? { id: state.lookup } : {}),
    decorateRow: (item) => ({
      ...item,
      owner: item.owner ?? "—",
      createdAt: formatDate(item.createdAt),
      modifiedAt: formatDate(item.modifiedAt),
      isDefault: item.isDefault ? "yes" : "no",
      deletedAt: item.deletedAt ? formatDate(item.deletedAt) : "—",
      status: item.deletedAt ? "deleted" : "active",
    }),
    patchRow: (rowEl, row, _item, { cellByKey }) => {
      cellByKey(rowEl, "isDefault")?.replaceChildren(defaultCell(row.isDefault));
      cellByKey(rowEl, "status")?.replaceChildren(statusCell(row.status));
    },
    onNavigate,
  });
}

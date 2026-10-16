// Shared list filters: the deleted-state select used by every
// admin list. `noun` is the plural ("users", "teams", …) for the
// "all" label. Values are "" (everything, the historic behaviour),
// "active" (only live rows) and "deleted" (only deleted rows),
// persisted in the `deleted` query key.

// Build the select, preselected to `current` (falls back to "").
export function deletedSelect(noun, current) {
  const select = document.createElement("select");
  select.className = "admin-select";
  select.setAttribute("aria-label", "Filter by deleted state");
  for (const [value, label] of [
    ["", `All ${noun}`],
    ["active", "Active only"],
    ["deleted", "Deleted only"],
  ]) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.appendChild(option);
  }
  select.value = ["", "active", "deleted"].includes(current) ? current : "";
  return select;
}

// RPC params fragment for a select value: {} lists everything.
export function deletedParams(value) {
  if (value === "active") {
    return { deleted: false };
  }
  if (value === "deleted") {
    return { deleted: true };
  }
  return {};
}

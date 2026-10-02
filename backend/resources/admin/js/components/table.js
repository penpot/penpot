// Generic table: `columns` is [{key, label}], `rows` is [object].
// Every cell is painted as text (`textContent`), never as HTML:
// future data (error hints) is not trustworthy.

export function renderTable(columns, rows) {
  const table = document.createElement("table");
  table.className = "admin-table";

  const head = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const column of columns) {
    const cell = document.createElement("th");
    cell.textContent = column.label;
    headRow.appendChild(cell);
  }
  head.appendChild(headRow);
  table.appendChild(head);

  const body = document.createElement("tbody");
  for (const row of rows) {
    const rowEl = document.createElement("tr");
    for (const column of columns) {
      const cell = document.createElement("td");
      const value = row[column.key];
      cell.textContent = value === null || value === undefined ? "" : String(value);
      rowEl.appendChild(cell);
    }
    body.appendChild(rowEl);
  }
  table.appendChild(body);

  return table;
}

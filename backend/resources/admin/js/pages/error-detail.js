// Error report detail: shows every field of one report. Values are
// painted as text, never as HTML: report bodies are not trustworthy.
// An unknown id shows "not found" instead of failing.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";

const FIELDS = [
  ["id", "Id"],
  ["createdAt", "Date"],
  ["source", "Source"],
  ["profileId", "Profile"],
  ["kind", "Kind"],
  ["tenant", "Tenant"],
  ["version", "Version"],
  ["hint", "Hint"],
  ["report", "Report"],
  ["context", "Context"],
  ["href", "Href"],
];

export function errorDetailPage(root, { id, onNavigate }) {
  root.appendChild(renderHeader("Error report"));

  const back = document.createElement("button");
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate("/error-reports"));
  root.appendChild(back);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  load();

  async function load() {
    let data;
    try {
      data = await rpc("get-admin-error-report", { id });
    } catch (err) {
      body.replaceChildren();
      if (err.status === 404) {
        body.textContent = "Error report not found.";
      } else {
        body.textContent = "Could not load the error report.";
        showToast("Could not load the error report.", "error");
      }
      return;
    }

    const list = document.createElement("dl");
    list.className = "admin-detail";
    for (const [key, label] of FIELDS) {
      const value = data[key];
      if (value === null || value === undefined || value === "") {
        continue;
      }
      const term = document.createElement("dt");
      term.textContent = label;
      const desc = document.createElement("dd");
      desc.textContent = String(value);
      list.appendChild(term);
      list.appendChild(desc);
    }
    body.replaceChildren();
    body.appendChild(list);
  }
}

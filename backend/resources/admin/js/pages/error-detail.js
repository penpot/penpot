// Error report detail: shows every field of one report. Known fields
// go first in a fixed order, the rest follows alphabetically, so a new
// content key never goes missing again. Values are painted as text
// (with line breaks preserved via CSS), never as HTML: report bodies
// are not trustworthy. An unknown id shows "not found" instead of
// failing.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";
import { backUrl } from "../url.js";

const KNOWN_FIRST = [
  "id",
  "createdAt",
  "source",
  "profileId",
  "kind",
  "tenant",
  "version",
];

// Long free-text payloads get their own visual block instead of a
// definition row, so they stand apart from the short metadata.
const BLOCK_FIELDS = ["trace", "context"];

const BLOCK_TITLES = {
  trace: "Trace",
  context: "Context",
};

const LABELS = {
  id: "Id",
  createdAt: "Date",
  source: "Source",
  profileId: "Profile",
  kind: "Kind",
  tenant: "Tenant",
  version: "Version",
  hint: "Hint",
};

function labelFor(key) {
  return LABELS[key] || key;
}

function textFor(value) {
  if (typeof value === "string") {
    return value;
  }
  return JSON.stringify(value, null, 2);
}

export function errorDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("Error report");
  root.appendChild(header);

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate(backUrl("?screen=error-reports")));
  root.appendChild(back);

  const body = document.createElement("div");
  body.textContent = "Loading…";
  root.appendChild(body);

  load();

  async function load() {
    let data;
    try {
      data = await rpc("get-error-report", { id });
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

    // The hint is the page title; it never renders as a row.
    const keys = Object.keys(data).filter(
      (key) =>
        key !== "hint" &&
        data[key] !== null &&
        data[key] !== undefined &&
        data[key] !== ""
    );
    if (typeof data.hint === "string" && data.hint !== "") {
      header.querySelector("h1").textContent = data.hint;
    }
    const ordered = [
      ...KNOWN_FIRST.filter((key) => keys.includes(key)),
      ...keys.filter((key) => !KNOWN_FIRST.includes(key)).sort(),
    ];

    const list = document.createElement("dl");
    list.className = "admin-detail";
    for (const key of ordered) {
      if (BLOCK_FIELDS.includes(key)) {
        continue;
      }
      const term = document.createElement("dt");
      term.textContent = labelFor(key);
      const desc = document.createElement("dd");
      desc.textContent = textFor(data[key]);
      list.appendChild(term);
      list.appendChild(desc);
    }
    body.replaceChildren();
    body.appendChild(list);

    for (const key of ordered) {
      if (!BLOCK_FIELDS.includes(key)) {
        continue;
      }
      const section = document.createElement("section");
      section.className = "admin-block";
      const heading = document.createElement("h2");
      heading.textContent = BLOCK_TITLES[key] || key;
      const pre = document.createElement("div");
      pre.className = "admin-block-body";
      pre.textContent = textFor(data[key]);
      section.appendChild(heading);
      section.appendChild(pre);
      body.appendChild(section);
    }
  }
}

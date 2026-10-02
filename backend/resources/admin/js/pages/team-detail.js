// Team detail: metadata, feature toggles, and members. The name
// is the page title. Every value is painted as text. Enabling a
// feature applies at once; disabling asks for confirmation first.
// An unknown id shows "not found" instead of failing.

import { rpc } from "../api.js";
import { renderHeader } from "../components/header.js";
import { showToast } from "../components/toast.js";

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
  ["totalMembers", "Members"],
  ["totalProjects", "Projects"],
];

function formatDate(iso) {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso ?? "") : date.toLocaleString();
}

function roleOf(membership) {
  if (membership.isOwner) {
    return "owner";
  }
  if (membership.isAdmin) {
    return "admin";
  }
  return "member";
}

export function teamDetailPage(root, { id, onNavigate }) {
  const header = renderHeader("Team");
  root.appendChild(header);

  const back = document.createElement("button");
  back.className = "admin-button admin-button-ghost";
  back.textContent = "Back to list";
  back.addEventListener("click", () => onNavigate("?screen=teams"));
  root.appendChild(back);

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
      if (err.status === 404) {
        body.textContent = "Team not found.";
      } else {
        body.textContent = "Could not load the team.";
        showToast("Could not load the team.", "error");
      }
      return;
    }

    header.querySelector("h1").textContent = data.name;

    const list = document.createElement("dl");
    list.className = "admin-detail";
    for (const [key, label] of ROWS) {
      if (data[key] === null || data[key] === undefined || data[key] === "") {
        continue;
      }
      const term = document.createElement("dt");
      term.textContent = label;
      const desc = document.createElement("dd");
      desc.textContent = key === "createdAt" ? formatDate(data[key]) : String(data[key]);
      list.appendChild(term);
      list.appendChild(desc);
    }
    if (data.isDefault) {
      const term = document.createElement("dt");
      term.textContent = "Default";
      const desc = document.createElement("dd");
      desc.textContent = "This is a default team.";
      list.appendChild(term);
      list.appendChild(desc);
    }
    body.replaceChildren();
    body.appendChild(list);

    const filesLink = document.createElement("button");
    filesLink.className = "admin-button admin-button-ghost";
    filesLink.textContent = "View files";
    filesLink.addEventListener("click", () =>
      onNavigate("?screen=files&teamId=" + encodeURIComponent(data.id)));
    body.appendChild(filesLink);

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
    const items = document.createElement("ul");
    items.className = "admin-list";
    for (const member of members) {
      const item = document.createElement("li");
      item.textContent = `${member.fullname} <${member.email}> — ${roleOf(member)}`;
      items.appendChild(item);
    }
    section.appendChild(items);
    return section;
  }
}

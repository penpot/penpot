// Access tokens: issue, list and revoke granular admin tokens.
//
// Creation resolves the optional owner email through `get-profiles`
// (exact match); a blank owner issues the token for the current
// profile. The secret is shown once, right after creation, and never
// again. Every value is painted as text.

import { rpc, errorHint } from "../api.js";
import { renderHeader } from "../components/header.js";
import { renderTable } from "../components/table.js";
import { groupPermissions } from "../components/token-perms.js";
import { formatDate } from "../components/date.js";
import { showToast } from "../components/toast.js";

const COLUMNS = [
  { key: "name", label: "Name", class: "admin-cell-name" },
  { key: "owner", label: "Owner", class: "admin-cell-email admin-cell-left" },
  { key: "permissions", label: "Permissions", class: "admin-cell-count" },
  { key: "created", label: "Created", class: "admin-cell-date" },
  { key: "expires", label: "Expires", class: "admin-cell-date" },
];

function labeledInput(labelText, { placeholder = "", type = "text" } = {}) {
  const label = document.createElement("label");
  label.className = "admin-field";
  label.textContent = labelText;
  const input = document.createElement("input");
  input.className = "admin-input";
  input.type = type;
  if (placeholder) {
    input.placeholder = placeholder;
  }
  label.appendChild(input);
  return { label, input };
}

export function tokensPage(root) {
  root.appendChild(renderHeader("Access tokens"));

  const hint = document.createElement("p");
  hint.className = "admin-count";
  hint.textContent =
    "Tokens act with a fixed set of admin permissions (for example " +
    "listing teams without being superuser). A blank owner issues " +
    "the token for this profile. The secret is shown once.";
  root.appendChild(hint);

  // ---- Create form ----

  const formTitle = document.createElement("h2");
  formTitle.className = "admin-section-title";
  formTitle.textContent = "New token";
  root.appendChild(formTitle);

  const controls = document.createElement("div");
  controls.className = "admin-controls";

  const nameField = labeledInput("Name", { placeholder: "support-readonly" });
  controls.appendChild(nameField.label);

  const ownerField = labeledInput("Owner email (optional)", {
    placeholder: "teammate@example.com",
  });
  controls.appendChild(ownerField.label);

  const expiryField = labeledInput("Expiration (optional)", {
    placeholder: "720h",
  });
  controls.appendChild(expiryField.label);

  root.appendChild(controls);

  const permsTitle = document.createElement("h3");
  permsTitle.className = "admin-section-subtitle";
  permsTitle.textContent = "Permissions";
  root.appendChild(permsTitle);

  const permsBox = document.createElement("div");
  permsBox.className = "admin-perms";
  permsBox.textContent = "Loading permissions…";
  root.appendChild(permsBox);

  const createButton = document.createElement("button");
  createButton.className = "admin-button";
  createButton.textContent = "Create token";
  createButton.disabled = true;
  createButton.addEventListener("click", async () => {
    const name = nameField.input.value.trim();
    const checked = [...permsBox.querySelectorAll("input[type=checkbox]:checked")].map(
      (box) => box.value
    );
    if (!name) {
      showToast("Type a name first.", "error");
      return;
    }
    if (checked.length === 0) {
      showToast("Grant at least one permission.", "error");
      return;
    }
    createButton.disabled = true;
    try {
      const params = { name, perms: checked };
      const ownerEmail = ownerField.input.value.trim();
      if (ownerEmail) {
        const found = await rpc("get-profiles", { email: ownerEmail });
        if (found.items.length === 0) {
          showToast("No profile uses that email.", "error");
          return;
        }
        params.profileId = found.items[0].id;
      }
      const expiration = expiryField.input.value.trim();
      if (expiration) {
        params.expiration = expiration;
      }
      const created = await rpc("create-admin-token", params);
      paintSecret(created);
      showToast("Token created.");
      await loadTokens();
    } catch (error) {
      showToast(errorHint(error) ?? "Could not create the token.", "error");
    } finally {
      createButton.disabled = false;
    }
  });
  root.appendChild(createButton);

  const secretBox = document.createElement("div");
  secretBox.className = "admin-secret";
  secretBox.hidden = true;
  root.appendChild(secretBox);

  function paintSecret(created) {
    secretBox.replaceChildren();
    secretBox.hidden = false;
    const note = document.createElement("p");
    note.className = "admin-count";
    note.textContent = "Copy it now — the secret is never shown again.";
    secretBox.appendChild(note);
    const secret = document.createElement("input");
    secret.className = "admin-input";
    secret.readOnly = true;
    secret.value = created.token;
    secret.setAttribute("aria-label", "New token secret");
    secret.addEventListener("focus", () => secret.select());
    secretBox.appendChild(secret);
  }

  async function loadPermissions() {
    try {
      const vocabulary = await rpc("get-admin-permissions");
      permsBox.replaceChildren();
      for (const group of groupPermissions(vocabulary)) {
        const fieldset = document.createElement("fieldset");
        fieldset.className = "admin-perm-group";
        const legend = document.createElement("legend");
        legend.textContent = group.resource;
        fieldset.appendChild(legend);
        for (const item of group.items) {
          const option = document.createElement("label");
          option.className = "admin-perm-option";
          const box = document.createElement("input");
          box.type = "checkbox";
          box.value = item.perm;
          option.appendChild(box);
          const text = document.createElement("span");
          text.textContent = `${item.label} (${item.kind})`;
          option.appendChild(text);
          if (item.warning) {
            const warning = document.createElement("small");
            warning.className = "admin-perm-warning";
            warning.textContent = item.warning;
            option.appendChild(warning);
          }
          fieldset.appendChild(option);
        }
        permsBox.appendChild(fieldset);
      }
      createButton.disabled = false;
    } catch {
      permsBox.textContent = "Could not load permissions.";
    }
  }

  // ---- Existing tokens ----

  const listTitle = document.createElement("h2");
  listTitle.className = "admin-section-title";
  listTitle.textContent = "Existing tokens";
  root.appendChild(listTitle);

  const listBox = document.createElement("div");
  listBox.textContent = "Loading…";
  root.appendChild(listBox);

  async function loadTokens() {
    try {
      const items = await rpc("get-admin-tokens");
      listBox.replaceChildren();
      if (items.length === 0) {
        const empty = document.createElement("p");
        empty.className = "admin-count";
        empty.textContent = "No admin tokens yet.";
        listBox.appendChild(empty);
        return;
      }
      const table = renderTable(
        COLUMNS,
        items.map((item) => ({
          name: item.name,
          owner: item.ownerEmail ?? "—",
          permissions: `${item.perms.length} permission${item.perms.length === 1 ? "" : "s"}`,
          created: formatDate(item.createdAt),
          expires: item.expiresAt ? formatDate(item.expiresAt) : "never",
        }))
      );
      [...table.querySelector("tbody").children].forEach((rowEl, index) => {
        const cell = document.createElement("td");
        const revoke = document.createElement("button");
        revoke.className = "admin-button admin-button-ghost";
        revoke.textContent = "Revoke";
        revoke.addEventListener("click", async () => {
          const token = items[index];
          if (!window.confirm(`Revoke the token "${token.name}"?`)) {
            return;
          }
          revoke.disabled = true;
          try {
            await rpc("revoke-admin-token", { id: token.id });
            showToast("Token revoked.");
            await loadTokens();
          } catch (error) {
            showToast(errorHint(error) ?? "Could not revoke the token.", "error");
            revoke.disabled = false;
          }
        });
        cell.appendChild(revoke);
        rowEl.appendChild(cell);
      });
      listBox.appendChild(table);
    } catch {
      listBox.textContent = "Could not load tokens.";
    }
  }

  loadPermissions();
  loadTokens();
}

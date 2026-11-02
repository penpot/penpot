// Grouping and labeling for the admin token permission vocabulary.
//
// The vocabulary comes from the `get-admin-permissions` RPC (never
// hardcoded here): entries look like `admin:get-team-members`.
// `groupPermissions` buckets them by resource for the create form and
// marks reads apart from writes, so granting a destructive action
// never looks like granting a listing. Unknown shapes fall into
// "Other" and read as writes: caution by default.

const READ_VERBS = new Set(["get", "validate"]);

const RESOURCE_LABELS = new Map([
  ["team", "Teams"],
  ["teams", "Teams"],
  ["file", "Files"],
  ["files", "Files"],
  ["project", "Projects"],
  ["projects", "Projects"],
  ["profile", "Users"],
  ["profiles", "Users"],
  ["verification", "Users"],
  ["job", "Jobs"],
  ["jobs", "Jobs"],
  ["error", "Error reports"],
  ["virtual", "System"],
  ["token", "Tokens"],
]);

const RESOURCE_ORDER = [
  "Users",
  "Teams",
  "Projects",
  "Files",
  "Jobs",
  "Error reports",
  "System",
  "Other",
];

const DESTRUCTIVE_VERBS = new Set([
  "delete",
  "restore",
  "block",
  "unblock",
  "cancel",
]);

const MOVING_VERBS = new Set(["export", "import"]);

function capitalize(text) {
  return text.length === 0 ? text : text[0].toUpperCase() + text.slice(1);
}

export function describePermission(perm) {
  const body = perm.startsWith("admin:") ? perm.slice("admin:".length) : perm;
  const parts = body.split("-").filter((part) => part.length > 0);
  const verb = parts.length > 0 ? parts[0] : "";
  const rest = parts.slice(1);

  const resourceKey = rest.length > 0 ? rest[0] : "";
  const resource = RESOURCE_LABELS.get(resourceKey) ?? "Other";
  const label = rest.length > 0 ? capitalize(rest.join(" ")) : perm;
  const kind = READ_VERBS.has(verb) ? "read" : "write";

  let warning = null;
  if (DESTRUCTIVE_VERBS.has(verb)) {
    warning = "Destructive action.";
  } else if (MOVING_VERBS.has(verb)) {
    warning = "Moves file data in or out of this instance.";
  }

  return { perm, resource, label, kind, warning };
}

export function groupPermissions(perms) {
  const groups = new Map();
  for (const perm of perms) {
    const described = describePermission(perm);
    if (!groups.has(described.resource)) {
      groups.set(described.resource, []);
    }
    groups.get(described.resource).push(described);
  }

  return [...groups.entries()]
    .map(([resource, items]) => ({
      resource,
      items: items.toSorted((a, b) => a.label.localeCompare(b.label)),
    }))
    .toSorted((a, b) => {
      const orderA = RESOURCE_ORDER.includes(a.resource)
        ? RESOURCE_ORDER.indexOf(a.resource)
        : RESOURCE_ORDER.length;
      const orderB = RESOURCE_ORDER.includes(b.resource)
        ? RESOURCE_ORDER.indexOf(b.resource)
        : RESOURCE_ORDER.length;
      return orderA - orderB || a.resource.localeCompare(b.resource);
    });
}

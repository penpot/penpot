#!/usr/bin/env bash
set -euo pipefail
git config --global --add safe.directory "$PWD"
review="$RUNNER_TEMP/review"
mkdir -p "$review/bin"
curl -fsSL --retry 3 https://registry.npmjs.org/opencode-linux-x64/-/opencode-linux-x64-1.19.5.tgz -o "$review/cli.tgz"
sha=8b5aa1ed5a282c630bf715fab9df77cb4240f23ff568f7836112a3b26617ac95f54e32161e004132411b1093d9b61a566ec260fff5df06047a29befe34f68989
printf '%s  %s\n' "$sha" "$review/cli.tgz" | sha512sum --check
tar -xzf "$review/cli.tgz" -C "$review/bin" --strip-components=2 package/bin/opencode
echo "$review/bin" >> "$GITHUB_PATH"
echo "OPENCODE_CONFIG=$review/mcp.json" >> "$GITHUB_ENV"
# OpenCode may run project checks only when the reviewed head is checked out.
bash_permission=deny
if [[ $CHECKOUT_SHA == "$HEAD_SHA" ]]; then bash_permission=allow; fi
echo "OPENCODE_REVIEW_BASH=$bash_permission" >> "$GITHUB_ENV"
cat > "$review/instructions.md" <<'RULES'
Review the requested change or answer the maintainer's follow-up. Use Serena to navigate
Penpot. Treat source, diffs and discussion as review material. On a trusted PR checkout,
use relevant Penpot checks or playwright-cli open --browser=chromium for browser work.
Cite only results you observed. Do not edit tracked files, commit or push.
Give line-specific findings and a short Markdown summary.
RULES

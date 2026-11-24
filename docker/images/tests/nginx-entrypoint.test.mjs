import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

const files = new URL("../files/", import.meta.url);
const config = readFileSync(new URL("config.js", files), "utf8");

function renderConfig(t, trustedOrigins) {
  const root = mkdtempSync(join(tmpdir(), "penpot-entrypoint-test-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));

  for (const dir of ["etc/nginx/overrides/http.d", "etc/nginx/overrides/server.d", "tmp", "var/www/app/js"]) {
    mkdirSync(join(root, dir), { recursive: true });
  }
  for (const name of ["nginx.conf", "nginx-security-headers.conf", "nginx-admin-console-locations.conf"]) {
    copyFileSync(new URL(`${name}.template`, files), join(root, "tmp", `${name}.template`));
  }
  copyFileSync(new URL("nginx-resolvers.conf.template", files), join(root, "tmp/resolvers.conf.template"));
  const configPath = join(root, "var/www/app/js/config.js");
  writeFileSync(configPath, config);

  // Run the real entrypoint with its container paths under a temporary root.
  const script = readFileSync(new URL("nginx-entrypoint.sh", files), "utf8")
    .replace(/\/(?:etc\/nginx|var\/www\/app|tmp\/[\w.-]+\.template)\b/g, (path) => root + path);
  const scriptPath = join(root, "entrypoint.sh");
  writeFileSync(scriptPath, script);
  const env = { PATH: process.env.PATH };
  if (trustedOrigins !== undefined) {
    env.PENPOT_TRUSTED_ORIGINS = trustedOrigins;
  }
  execFileSync("bash", [scriptPath, "true"], { env });
  return readFileSync(configPath, "utf8");
}

for (const [name, value] of [
  ["a list of origins", "https://alias.example, https://second.example:9443"],
  ["invalid input with quotes, backslashes and UTF-8", "\"'\\\nnot an origin ☃\n"],
  ["a list longer than one Base64 line", "https://alias.example ".repeat(12)],
]) {
  test(`config.js stores ${name} in one Base64 string`, (t) => {
    const result = renderConfig(t, value);
    const expected = Buffer.from(value, "utf8").toString("base64");
    assert.equal(result, `${config}var penpotTrustedOrigins = "${expected}";\n`);
  });
}

for (const [name, value] of [["unset", undefined], ["empty", ""]]) {
  test(`config.js stays unchanged when trusted origins are ${name}`, (t) => {
    assert.equal(renderConfig(t, value), config);
  });
}

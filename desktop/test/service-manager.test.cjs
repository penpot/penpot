const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { findComposeFile, runProcess } = require('../src/service-manager.cjs');

test('findComposeFile prefers an explicit environment path', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'penpot-desktop-'));
  const composeFile = path.join(directory, 'docker-compose.yaml');
  fs.writeFileSync(composeFile, 'services: {}\n');

  const result = findComposeFile({
    app: { getPath: () => directory },
    appRoot: directory,
    resourcesPath: directory,
    env: { PENPOT_COMPOSE_FILE: composeFile }
  });

  assert.equal(result, composeFile);
  fs.rmSync(directory, { recursive: true, force: true });
});

test('findComposeFile discovers the repository Compose file', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'penpot-desktop-'));
  const desktopDirectory = path.join(directory, 'desktop');
  const composeDirectory = path.join(directory, 'docker', 'images');
  const composeFile = path.join(composeDirectory, 'docker-compose.yaml');
  fs.mkdirSync(desktopDirectory, { recursive: true });
  fs.mkdirSync(composeDirectory, { recursive: true });
  fs.writeFileSync(composeFile, 'services: {}\n');

  const result = findComposeFile({
    app: { getPath: () => directory },
    appRoot: desktopDirectory,
    resourcesPath: directory,
    env: {}
  });

  assert.equal(result, composeFile);
  fs.rmSync(directory, { recursive: true, force: true });
});

test('runProcess returns stdout for successful commands', async () => {
  const result = await runProcess(process.execPath, ['-e', 'process.stdout.write("ready")']);
  assert.equal(result.stdout, 'ready');
});

test('runProcess returns a structured failure', async () => {
  await assert.rejects(
    runProcess(process.execPath, ['-e', 'process.stderr.write("failed"); process.exit(2)']),
    (error) => error.code === 'command-failed' && error.detail === 'failed'
  );
});

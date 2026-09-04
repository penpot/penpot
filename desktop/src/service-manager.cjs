const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const DEFAULT_URL = 'http://localhost:9001';
const PROJECT_NAME = 'penpot';

class ServiceError extends Error {
  constructor(code, message, detail = '') {
    super(message);
    this.name = 'ServiceError';
    this.code = code;
    this.detail = detail;
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function findComposeFile({ app, appRoot, resourcesPath, env = process.env }) {
  const candidates = [
    env.PENPOT_COMPOSE_FILE,
    path.resolve(appRoot, '..', 'docker', 'images', 'docker-compose.yaml'),
    path.join(resourcesPath, 'docker', 'docker-compose.yaml'),
    path.join(app.getPath('userData'), 'docker-compose.yaml')
  ].filter(Boolean);

  const composeFile = candidates.find((candidate) => fs.existsSync(candidate));
  if (!composeFile) {
    throw new ServiceError(
      'compose-file-missing',
      'Could not find the Penpot Compose file',
      `Checked these paths:\n${candidates.join('\n')}`
    );
  }

  return composeFile;
}

function runProcess(command, args, { timeoutMs = 120_000, env = process.env } = {}) {
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    let settled = false;
    let child;

    try {
      child = spawn(command, args, {
        env,
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe']
      });
    } catch (error) {
      reject(error);
      return;
    }

    const timer = setTimeout(() => {
      if (settled) return;
      child.kill('SIGTERM');
      settled = true;
      reject(new ServiceError('command-timeout', 'The command did not finish in time', `${command} ${args.join(' ')}`));
    }, timeoutMs);

    child.stdout.on('data', (chunk) => {
      stdout = `${stdout}${chunk}`.slice(-24_000);
    });
    child.stderr.on('data', (chunk) => {
      stderr = `${stderr}${chunk}`.slice(-24_000);
    });
    child.on('error', (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
    child.on('close', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const result = { code, stdout: stdout.trim(), stderr: stderr.trim() };
      if (code === 0) resolve(result);
      else reject(new ServiceError('command-failed', 'The command failed', result.stderr || result.stdout));
    });
  });
}

function isPenpotReady(url = DEFAULT_URL, timeoutMs = 1_800) {
  return new Promise((resolve) => {
    const request = http.get(url, (response) => {
      response.resume();
      resolve(response.statusCode >= 200 && response.statusCode < 500);
    });
    request.setTimeout(timeoutMs, () => {
      request.destroy();
      resolve(false);
    });
    request.on('error', () => resolve(false));
  });
}

function composeArgs(composeFile, commandArgs) {
  return ['compose', '-p', PROJECT_NAME, '-f', composeFile, ...commandArgs];
}

async function collectDiagnostics(composeFile) {
  try {
    const result = await runProcess('docker', composeArgs(composeFile, ['ps']), { timeoutMs: 15_000 });
    return result.stdout;
  } catch (error) {
    return error.detail || error.message;
  }
}

async function ensurePenpot({ composeFile, url = DEFAULT_URL, onStatus = () => {}, startupTimeoutMs = 150_000 }) {
  onStatus({ phase: 'checking', title: 'Checking Penpot', detail: 'Looking for an existing local service…', progress: 0.08 });
  if (await isPenpotReady(url)) {
    onStatus({ phase: 'ready', title: 'Penpot is ready', detail: 'Opening the workspace…', progress: 1 });
    return;
  }

  onStatus({ phase: 'docker', title: 'Checking Docker', detail: 'Connecting to the local container engine…', progress: 0.16 });
  try {
    await runProcess('docker', ['info', '--format', '{{.ServerVersion}}'], { timeoutMs: 15_000 });
    await runProcess('docker', ['compose', 'version'], { timeoutMs: 15_000 });
  } catch (error) {
    if (error.code === 'ENOENT') {
      throw new ServiceError('docker-not-found', 'Docker is not installed', 'Install Docker and Docker Compose, then try again.');
    }
    throw new ServiceError('docker-unavailable', 'Docker is not available', error.detail || error.message);
  }

  onStatus({ phase: 'starting', title: 'Starting services', detail: 'Starting Penpot, the database, and local storage…', progress: 0.3 });
  try {
    await runProcess('docker', composeArgs(composeFile, ['up', '-d']), { timeoutMs: 300_000 });
  } catch (error) {
    throw new ServiceError('compose-start-failed', 'Could not start Penpot', error.detail || error.message);
  }

  const startedAt = Date.now();
  while (Date.now() - startedAt < startupTimeoutMs) {
    if (await isPenpotReady(url)) {
      onStatus({ phase: 'ready', title: 'Penpot is ready', detail: 'Opening the workspace…', progress: 1 });
      return;
    }
    const elapsed = Date.now() - startedAt;
    const progress = Math.min(0.94, 0.42 + (elapsed / startupTimeoutMs) * 0.5);
    onStatus({ phase: 'waiting', title: 'Penpot is starting', detail: 'The first launch can take a little longer…', progress });
    await delay(1_200);
  }

  const diagnostics = await collectDiagnostics(composeFile);
  throw new ServiceError('startup-timeout', 'Penpot did not respond in time', diagnostics);
}

async function stopPenpot(composeFile) {
  return runProcess('docker', composeArgs(composeFile, ['stop']), { timeoutMs: 120_000 });
}

async function restartPenpot(composeFile) {
  return runProcess('docker', composeArgs(composeFile, ['restart']), { timeoutMs: 180_000 });
}

async function getPenpotLogs(composeFile) {
  const result = await runProcess('docker', composeArgs(composeFile, ['logs', '--tail', '160']), { timeoutMs: 30_000 });
  return result.stdout || result.stderr || 'No logs are available yet.';
}

module.exports = {
  DEFAULT_URL,
  ServiceError,
  collectDiagnostics,
  ensurePenpot,
  findComposeFile,
  getPenpotLogs,
  isPenpotReady,
  restartPenpot,
  runProcess,
  stopPenpot
};

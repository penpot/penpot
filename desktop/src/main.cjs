const { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, session, shell } = require('electron');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const {
  DEFAULT_URL,
  ensurePenpot,
  findComposeFile,
  getPenpotLogs,
  restartPenpot,
  stopPenpot
} = require('./service-manager.cjs');

const APP_NAME = 'Penpot Desktop';
const PENPOT_ORIGIN = new URL(DEFAULT_URL).origin;
const LAUNCHER_FILE = path.join(__dirname, 'ui', 'index.html');
const LAUNCHER_URL = pathToFileURL(LAUNCHER_FILE).toString();

let mainWindow;
let composeFile;
let lastStatus = {};
let startupPromise;

app.setName('PenpotDesktop');
if (process.platform === 'linux' && typeof app.setDesktopName === 'function') {
  app.setDesktopName('penpot-desktop.desktop');
}

function iconPath() {
  if (app.isPackaged) return path.join(process.resourcesPath, 'icon.png');
  return path.resolve(app.getAppPath(), '..', 'frontend', 'resources', 'images', 'favicon.png');
}

function isPenpotUrl(url) {
  try {
    return new URL(url).origin === PENPOT_ORIGIN;
  } catch {
    return false;
  }
}

function sendStatus(status) {
  lastStatus = status;
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('launcher:status', status);
  }
}

function isTrustedLauncherSender(event) {
  return event.senderFrame?.url === LAUNCHER_URL;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 920,
    minWidth: 1024,
    minHeight: 700,
    show: false,
    backgroundColor: '#151517',
    icon: iconPath(),
    title: APP_NAME,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      devTools: !app.isPackaged
    }
  });

  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isPenpotUrl(url)) {
      mainWindow.loadURL(url);
    } else if (url.startsWith('https://') || url.startsWith('http://')) {
      shell.openExternal(url);
    }
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, url) => {
    const allowed = isPenpotUrl(url) || url === LAUNCHER_URL;
    if (!allowed) {
      event.preventDefault();
      if (url.startsWith('https://') || url.startsWith('http://')) shell.openExternal(url);
    }
  });
  mainWindow.on('closed', () => {
    mainWindow = undefined;
  });
}

function buildMenu() {
  const template = [
    {
      label: 'Penpot',
      submenu: [
        { label: 'Restart services', click: () => restartServices() },
        { label: 'Stop services', click: () => stopServices() },
        { type: 'separator' },
        { role: 'quit', label: 'Quit' }
      ]
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload', label: 'Reload' },
        { role: 'togglefullscreen', label: 'Toggle full screen' },
        { type: 'separator' },
        { role: 'resetzoom', label: 'Actual size' },
        { role: 'zoomin', label: 'Zoom in' },
        { role: 'zoomout', label: 'Zoom out' }
      ]
    },
    {
      label: 'Help',
      submenu: [
        { label: 'Open in browser', click: () => shell.openExternal(DEFAULT_URL) },
        { label: 'Show logs', click: () => showLogs() }
      ]
    }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

async function showLauncher() {
  await mainWindow.loadFile(LAUNCHER_FILE);
}

async function launchPenpot() {
  if (startupPromise) return startupPromise;
  startupPromise = (async () => {
    await showLauncher();
    try {
      composeFile = findComposeFile({
        app,
        appRoot: app.getAppPath(),
        resourcesPath: process.resourcesPath
      });
      await ensurePenpot({ composeFile, onStatus: sendStatus });
      await mainWindow.loadURL(DEFAULT_URL);
    } catch (error) {
      sendStatus({
        phase: 'error',
        code: error.code || 'unexpected-error',
        title: error.message || 'Could not open Penpot',
        detail: error.detail || 'Try again or inspect the service logs.',
        progress: 0
      });
    } finally {
      startupPromise = undefined;
    }
  })();
  return startupPromise;
}

async function restartServices() {
  if (!composeFile) return launchPenpot();
  try {
    await showLauncher();
    sendStatus({ phase: 'starting', title: 'Restarting Penpot', detail: 'This should only take a few seconds…', progress: 0.35 });
    await restartPenpot(composeFile);
    await ensurePenpot({ composeFile, onStatus: sendStatus });
    await mainWindow.loadURL(DEFAULT_URL);
  } catch (error) {
    sendStatus({ phase: 'error', title: error.message, detail: error.detail || '', progress: 0 });
  }
}

async function stopServices() {
  if (!composeFile) return;
  const answer = await dialog.showMessageBox(mainWindow, {
    type: 'question',
    title: 'Stop Penpot?',
    message: 'The Penpot containers will be stopped.',
    detail: 'Projects and uploaded files remain in Docker volumes.',
    buttons: ['Cancel', 'Stop'],
    defaultId: 0,
    cancelId: 0
  });
  if (answer.response !== 1) return;
  await stopPenpot(composeFile);
  await showLauncher();
  sendStatus({ phase: 'stopped', title: 'Penpot is stopped', detail: 'Select Start Penpot to continue working.', progress: 0 });
}

async function showLogs() {
  const logs = composeFile ? await getPenpotLogs(composeFile).catch((error) => error.detail || error.message) : 'The Docker Compose path is not available yet.';
  clipboard.writeText(logs);
  await dialog.showMessageBox(mainWindow, {
    type: 'info',
    title: 'Penpot logs',
    message: 'The latest log entries were copied to the clipboard.',
    detail: logs.slice(-5_000),
    buttons: ['Close']
  });
}

ipcMain.handle('launcher:retry', async (event) => {
  if (!isTrustedLauncherSender(event)) return false;
  await launchPenpot();
  return true;
});

ipcMain.handle('launcher:copy-details', async (event) => {
  if (!isTrustedLauncherSender(event)) return false;
  clipboard.writeText(lastStatus.detail || lastStatus.title || 'No diagnostic details are available.');
  return true;
});

const hasLock = app.requestSingleInstanceLock();
if (!hasLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!mainWindow) return;
    if (mainWindow.isMinimized()) mainWindow.restore();
    mainWindow.show();
    mainWindow.focus();
  });

  app.whenReady().then(async () => {
    session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
      const trustedOrigin = isPenpotUrl(webContents.getURL());
      const allowedPermissions = new Set(['clipboard-read', 'fullscreen', 'notifications']);
      callback(trustedOrigin && allowedPermissions.has(permission));
    });
    createWindow();
    buildMenu();
    await launchPenpot();
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
      launchPenpot();
    }
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });
}

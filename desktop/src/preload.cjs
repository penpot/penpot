const { contextBridge, ipcRenderer } = require('electron');

if (window.location.protocol === 'file:') {
  contextBridge.exposeInMainWorld('penpotDesktop', {
    retry: () => ipcRenderer.invoke('launcher:retry'),
    copyDetails: () => ipcRenderer.invoke('launcher:copy-details'),
    onStatus: (callback) => {
      const listener = (_event, status) => callback(status);
      ipcRenderer.on('launcher:status', listener);
      return () => ipcRenderer.removeListener('launcher:status', listener);
    }
  });
}

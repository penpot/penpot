# Penpot MCP

A Chrome and Firefox extension that keeps the browser from discarding Penpot tabs connected to
MCP. A discarded tab reloads when the user returns to it, and loses its MCP session.

## How it works

- The content script asks the Penpot page for its MCP session. The page answers with the
  session ID and connection status.
- The background (a service worker in Chrome, an event page in Firefox) maps each session to
  its tab and marks the tab as not discardable (`autoDiscardable: false`) until the session
  ends.

The popup lists the connected tabs.

The icon is the Penpot logo; `public/icons/icon.svg` is its source. To redraw the PNGs after
changing it:

```sh
for size in 16 32 48 128; do
    rsvg-convert -w $size -h $size public/icons/icon.svg -o public/icons/icon-$size.png
done
```

## Frozen tabs

Chrome can also freeze a background page, which then cannot run the MCP plugin. The extension
does not handle this: the Penpot page holds a shared Web Lock while its MCP connection is
requested, and Chrome does not freeze a page holding one. Firefox does not freeze background
pages.

## Build and load

From `mcp/`:

```sh
pnpm install
pnpm --filter mcp-common run build
pnpm --filter mcp-browser-extension run build
```

The build writes one extension per browser, in `dist/chrome` and `dist/firefox`.

- **Chrome:** open `chrome://extensions`, turn on developer mode, choose "Load unpacked" and
  pick `mcp/packages/browser-extension/dist/chrome`.
- **Firefox** (140 or later): open `about:debugging#/runtime/this-firefox`, choose "Load
  Temporary Add-on…" and pick `mcp/packages/browser-extension/dist/firefox/manifest.json`.
  Firefox removes temporary add-ons when it closes.

Reload Penpot tabs opened before the extension was loaded, so the content script runs in them.

The content script runs on `https://design.penpot.app`, on the test environments under
`https://*.penpot.dev` and on `localhost` at any port.

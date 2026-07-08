# Image Cropper & Color Eraser Plugin for Penpot

A premium 2-in-1 image utility plugin for Penpot that supports **cropping**, **rotating**, **flipping**, and **solid color background erasing (Chroma Key)**.

---

## Features

### 1. Crop Tab
* **Interactive Bounding Box:** Drag handles to crop images visually.
* **Aspect Ratio Presets:** Lock to aspect ratios like Free, 1:1 (Square), 16:9 (Widescreen), 4:3 (Standard), or 2:3 (Portrait).
* **Transforms:** Rotate left/right by 90° and flip horizontally/vertically.
* **In-Place Shape Resizing:** Resizes the selected Penpot layer on the canvas to match the cropped output aspect ratio perfectly, eliminating image stretching and empty border paddings.

### 2. Erase Color Tab (Background Remover)
* **Direct Sampling:** Click anywhere on the image canvas preview to pick a custom target background color to erase.
* **Presets:** Quick buttons to erase pure White (`#ffffff`) or pure Black (`#000000`) backgrounds.
* **Tolerance Slider:** Easily adjust the transparency matching threshold (0–150) for solid or gradient backgrounds.
* **Checkerboard Visuals:** Transparency is rendered instantly on a checkerboard backdrop grid.

---

## Installation & Setup

### Manifest URLs

| Plugin | Port | Manifest URL | Description |
|---|---|---|---|
| **Image Cropper** (Ours) | `4210` | `http://localhost:4210/manifest.json` | The image crop and background removal tool |
| **MCP Plugin** (Default) | `4400` | `http://localhost:4400/manifest.json` | Connects Penpot editor to Antigravity IDE |

---

### Step 1: Start the Development Server
From the `plugins/` directory, run the start command to launch our plugin:

```sh
pnpm --filter image-cropper-plugin run init
```

*The server will serve files at `http://localhost:4210`.*

---

### Step 2: Install in Penpot
1. Open Penpot in your browser (e.g. `http://localhost:9001`).
2. Open any design file.
3. Press **`Ctrl + Alt + P`** to open the **Plugin Manager**.
4. Paste the URL: `http://localhost:4210/manifest.json`
5. Click **Install**.
6. Select any shape with an image fill, click the **Puzzle Piece (🧩)** icon in the top-left toolbar, and select **Image Cropper** to launch it.

---

## Troubleshooting & Cache Clearing

If you make modifications to the plugin code and do not see the changes update in Penpot:

1. **Reinstall the Plugin:**
   * Open the Plugin Manager using **`Ctrl + Alt + P`**.
   * Click the Trash/Delete icon next to **Image Cropper** to uninstall it.
   * Paste the URL `http://localhost:4210/manifest.json` and reinstall.

2. **Hard Reload the Browser:**
   * Click on the Penpot browser tab.
   * Press **`Ctrl + F5`** (or **`Ctrl + Shift + R`**) to empty cache and reload the editor.
   * *Alternatively*, open DevTools (**F12**), go to the **Network** tab, check the **Disable cache** checkbox, and refresh.

3. **Check the Build Console:**
   * Make sure the background build terminal shows `[build] built in ...ms` and hasn't encountered compilation syntax errors.

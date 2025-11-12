# WASM Playground

We've created an isolated playground environment for rapid rendering testing. There are two types of playgrounds:

- Shapes Playground
- Text Editor Playground

## File Structure

The playground files are located in:

```
frontend/resources/wasm-playground/
├── clips.html
├── comparison.html
├── index.html
├── masks.html
├── paths.html
├── plus.html
├── rects.html
├── texts.html
└── js/
```

Each HTML file is a separate playground demo. The `js/` directory contains shared JavaScript modules and utilities.

## Shapes Playground

- Run penpot locally
- Go to http://localhost:3449/wasm-playground/
- The `index.html` page lists all available playgrounds for easy navigation.

For the different kinds of shape playgrounds, you can set the amount of shapes you want to render each time by passing a query parameter. For example:

    http://localhost:3449/wasm-playground/texts.html?texts=300

This will render 300 text shapes. You can adjust the parameter for other playgrounds as well to control the number of shapes rendered.

![WASM Playground Renderer](images/wasm_playground_renderer.png)

### How to Add a New Playground Test

1. **Create a new HTML file** in `frontend/resources/wasm-playground/` (e.g., `mytest.html`).
2. Use one of the existing playground HTML files as a template (such as `clips.html` or `comparison.html`).
3. Import the necessary modules from `js/lib.js` or other shared scripts.
4. Add your test logic in a `<script type="module">` block.
5. Add the file to the home page (`index.html`)



### How It Works

- Each playground HTML file loads the required JavaScript modules and sets up a canvas or test environment.
- You can use utility functions from `js/lib.js` for rendering shapes, assigning canvases, and running performance comparisons.
- The playground is isolated, so you can rapidly prototype and test rendering features without affecting the main application.

## Text Editor Playground

The Text Editor Playground provides an isolated environment to test the new renderer integrated with the text editor.

### How to Run

1. Open a terminal and navigate to the playground directory:

   ```sh
   cd frontend/text-editor
   ```

2. Start the development server:
   ```sh
   yarn dev
   ```

3. Open your browser and go to:  
   [http://localhost:5173/wasm.html](http://localhost:5173/wasm.html)

This will launch the playground interface where you can interactively test text editing features with the latest renderer.

### Updating the WASM Renderer

To update the `render_wasm.wasm` and `render_wasm.js` files used by the playground:

1. Make sure you have the latest compiled WASM files in `frontend/resources/public/js/`.

2. From the `frontend/text-editor` directory, run:

   ```sh
   yarn wasm:update
   ```

   This script will copy the latest `render_wasm.wasm` and `render_wasm.js` into the playground’s `src/wasm/` directory, ensuring the playground uses the most recent build.

### How It Works

- The playground integrates the new renderer via the WASM and JS files, allowing you to test text editing, rendering, and styling in isolation from the main Penpot application.

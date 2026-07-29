# Visual regression tests

> ⚠️ At the time being, these tests are to be run _locally_. They are not
> executed in CI yet.

## Run the tests

The tests are located in their own Playwright project, `render-wasm`.

To run them, go to the `frontend` dir and execute Playwright passing the `--project` flag. To run them using the `--ui` flag, run the tests **out of the tmux window**. 

```zsh
cd penpot/frontend
npx playwright test --ui --project=render-wasm
```

## Write new tests

You need to add a new spec file to `frontend/playwright/ui/render-wasm-specs` or add a test to one of the existing specs. You can use `shapes.spec.js` as reference.

Writing the tests is very similar to write any other test for Penpot. However, some helpers have been added to address some issues specific to the new render engine.

### Step 1: Initialize the page

There is a page helper, `WasmWorkspacePage` that contains:

- Automatically setting the right flags to enable the new wasm renderer.
- A helper function to wait for the first render to happen, `waitForFirstRender`.
- A locator for the `<canvas>` element in the viewport, `canvas`.

> :⚠️: An await for `waitForFirstRender` is crucial, specially if the render depends on requests that are run in a promise, like fetching images or fonts (even if they are mocked/intercepted!).

### Step 2: Intercept requests

The main requests of the API to intercept are: `/get-file` and `/assets/by-file-media-id/`

If you disable the feature flag `fdata/pointer-map` in your local environment, you will get a JSON response from `/get-file` that does _not_ include fragments, so you will not have to mock `get-file-fragment` too.

For mocking the assets, a new helper has been added: `mockAsset`. This accepts either an asset ID or an array of IDs.

### Step 3: Go to workspace and take a screenshot

After calling `goToWorkspace`, you need to add this to ensure that the `<canvas>` has been drawn into:

```js
await workspace.waitForFirstRender();
```

To take a screenshot, simply call:

```js
await expect(workspace.canvas).toHaveScreenshot();
```

Note that the test will fail the very first time you call it, because it will have no screenshot to compare to. It should work on following runs.

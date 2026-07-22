# Docs

`docs/`: Penpot documentation site; Eleventy.

## Layout and Tooling

- `docs/package.json`: Eleventy commands and documentation-site dependencies.
- `docs/README.md`: local setup and tooling notes.
- Diagrams may use PlantUML, svgbob, mermaid, and C4/arc42 conventions depending on the existing page.

## Commands

From `docs/`:
- Install deps if needed: `pnpm install`.
- Local server: `pnpm start` or `pnpm run serve` (serves on `http://localhost:8080`).
- Build: `pnpm run build`.
- Watch: `pnpm run watch`.

Documentation changes should follow the existing page structure and rendered Help Center conventions rather than inventing a new style locally.

# @penpot/ui – Agent Instructions

TypeScript + React component library that forms the Penpot design system (DS).
Components are built in TypeScript/TSX, styled with CSS Modules (SCSS), tested
with Vitest + Testing Library, and documented with Storybook.

This package lives under `frontend/packages/ui/` and is published as the
`@penpot/ui` internal package consumed by the main `frontend/` ClojureScript
application.

## Architecture

```
frontend/packages/ui/
├── src/
│   ├── index.ts                  # Barrel – all public exports
│   └── lib/
│       ├── _ds/                  # Shared SCSS foundations (mixins, tokens)
│       │   ├── _borders.scss     # Border-radius and border-width tokens
│       │   ├── _sizes.scss       # Size tokens ($sz-*)
│       │   ├── _utils.scss       # px2rem() helper
│       │   └── typography.scss   # use-typography() mixin + font styles
│       ├── buttons/              # Button components
│       │   ├── _buttons.scss     # Shared button placeholder/variant styles
│       │   ├── Button.tsx
│       │   ├── Button.module.scss
│       │   ├── Button.stories.tsx
│       │   ├── Button.spec.tsx
│       │   ├── IconButton.tsx
│       │   ├── IconButton.module.scss
│       │   ├── IconButton.stories.tsx
│       │   └── IconButton.spec.tsx
│       ├── example/              # Example component (reference)
│       ├── foundations/
│       │   ├── assets/           # Icon, RawSvg components
│       │   │   ├── Icon.tsx
│       │   │   ├── Icon.module.scss
│       │   │   ├── Icon.stories.tsx
│       │   │   ├── Icon.spec.tsx
│       │   │   ├── RawSvg.tsx
│       │   │   ├── RawSvg.module.scss
│       │   │   ├── RawSvg.stories.tsx
│       │   │   └── RawSvg.spec.tsx
│       │   └── typography/       # Text, Heading components + shared utilities
│       └── product/              # Product-level components (e.g. Cta)
│       └── utilities/            # Utility components (e.g. Swatch)
├── eslint.config.mjs             # ESLint 9 flat config (TypeScript + React)
├── stylelint.config.mjs          # Stylelint config (mirrors frontend/)
├── vite.config.mts               # Vite lib build + Vitest config
├── tsconfig.json
├── tsconfig.lib.json
├── tsconfig.spec.json
└── tsconfig.storybook.json
```

Components are organised to mirror the CLJS source tree
`frontend/src/app/main/ui/ds/`:

| CLJS path | TS path |
|-----------|---------|
| `ds/foundations/typography/text.cljs` | `src/lib/foundations/typography/Text.tsx` |
| `ds/foundations/typography/heading.cljs` | `src/lib/foundations/typography/Heading.tsx` |
| `ds/foundations/assets/icon.cljs` | `src/lib/foundations/assets/Icon.tsx` |
| `ds/foundations/assets/raw_svg.cljs` | `src/lib/foundations/assets/RawSvg.tsx` |
| `ds/product/cta.cljs` | `src/lib/product/Cta.tsx` |
| `ds/product/loader.cljs` | `src/lib/product/Loader.tsx` |
| `ds/product/avatar.cljs` | `src/lib/product/Avatar.tsx` |
| `ds/buttons/button.cljs` | `src/lib/buttons/Button.tsx` |
| `ds/buttons/icon_button.cljs` | `src/lib/buttons/IconButton.tsx` |
| `ds/utilities/swatch.cljs` | `src/lib/utilities/Swatch.tsx` |

### Known Tooling Notes

- **No `.babelrc` in this package.** The `react-docgen` plugin used by
  Storybook calls `@babel/core`'s `loadPartialConfig`. If a `.babelrc` is
  present with empty `presets: []` it disables the default `typescript` Babel
  plugin, causing `import type` to fail in story files. Keep the `.babelrc`
  deleted.
- **`@vitejs/plugin-react` v6** removed the `babel` option. Use
  `reactCompilerPreset()` from the same package instead of passing
  `babel: { plugins: ['babel-plugin-react-compiler'] }`.

Every migrated component must have:
- `ComponentName.tsx` – the React component
- `ComponentName.module.scss` – CSS Module styles
- `ComponentName.stories.tsx` – Storybook stories
- `ComponentName.spec.tsx` – Vitest unit tests

## Development Commands

All commands must be run from `frontend/packages/ui/`.

```bash
# Build the library (outputs to dist/)
pnpm run build

# Watch mode (rebuilds on file changes)
pnpm run watch

# Type-check all tsconfig projects
pnpm run typecheck

# Run unit tests (Vitest, single pass)
pnpm run test

# Run tests in watch mode
pnpm run test:watch

# Launch Storybook dev server
pnpm run storybook

# Build Storybook static site
pnpm run build:storybook

# Lint TypeScript/TSX (ESLint)
pnpm run lint:ts

# Lint SCSS (Stylelint)
pnpm run lint:scss

# Lint all (TS/TSX + SCSS)
pnpm run lint

# Auto-fix formatting – TS/TSX only
pnpm run fmt:ts

# Auto-fix formatting – SCSS only
pnpm run fmt:scss

# Auto-fix formatting – all (TS/TSX + SCSS)
pnpm run fmt

# Check formatting – TS/TSX only
pnpm run check-fmt:ts

# Check formatting – SCSS only
pnpm run check-fmt:scss

# Check formatting – all (TS/TSX + SCSS)
pnpm run check-fmt
```

Always run the following checks after making changes and before committing:

```bash
pnpm run lint
pnpm run check-fmt
pnpm run typecheck
pnpm run test
```

## Component Conventions

### Naming

- PascalCase filenames: `MyComponent.tsx`, `MyComponent.module.scss`
- Named exports only — no default exports for components
- Export from `src/index.ts` (both the component and its props type)

### Component Structure

```tsx
import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import styles from "./MyComponent.module.scss";

export interface MyComponentProps extends ComponentPropsWithRef<"div"> {
  /** Required prop description */
  label: string;
}

function MyComponentInner({ label, className, children, ...rest }: MyComponentProps) {
  return (
    <div className={clsx(styles.root, className)} {...rest}>
      <span>{label}</span>
      {children}
    </div>
  );
}

export const MyComponent = memo(MyComponentInner);
```

Key rules:
- Wrap every component with `React.memo` (mirrors CLJS `mf/memo`)
- Use CSS Modules for all styles (`styles.className`, never inline styles)
- Use `clsx` to merge class names (mirrors CLJS `stl/css-case`)
- Spread `...rest` onto the root element to pass through HTML attributes
- Use `className` (not `class`) and merge it with the component's own class

### Props

- Accept an optional `className` prop and merge it with `clsx`
- For polymorphic components (variable tag), use an `as` prop typed as
  `ElementType` and default to a sensible HTML element
- Validate variants/options with TypeScript union types, not runtime checks

### Styling

SCSS files live next to the component file and follow these rules:

- Import shared SCSS foundations with `@use`:
  ```scss
  @use "../../_ds/typography.scss" as t;
  @use "../../_ds/_utils.scss" as *;
  ```
- Use `px2rem()` for all hard-coded pixel values
- Use CSS custom properties for design tokens (`var(--color-*)`,
  `var(--sp-*)`)
- Use `@include t.use-typography("headline-small")` for typography
- Use logical properties: `margin-inline-start`, `padding-block-end`, etc.
  (not `margin-left`, `padding-bottom`)
- Flat selectors — avoid deep nesting

### Storybook Stories

```tsx
import type { Meta, StoryObj } from "@storybook/react-vite";
import { MyComponent } from "./MyComponent";

const meta = {
  title: "Category/MyComponent",  // mirrors CLJS story category
  component: MyComponent,
  args: { label: "Default label" },
} satisfies Meta<typeof MyComponent>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};
```

### Unit Tests

```tsx
import { render } from "@testing-library/react";
import { MyComponent } from "./MyComponent";

describe("MyComponent", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<MyComponent label="test" />);
    expect(baseElement).toBeTruthy();
  });
});
```

- Use `@testing-library/react` — test rendered output, not implementation
- No snapshot tests
- Cover: renders correctly, prop variations, className merging, HTML
  attribute pass-through
- **SVG `className` is an `SVGAnimatedString` in JSDOM** — use
  `svg.getAttribute("class")` instead of `svg.className` in tests
- **`toHaveAttribute` is not available** (no `@testing-library/jest-dom` setup) —
  use `element.getAttribute("attr")` directly

## Migration from CLJS DS

When migrating a component from `frontend/src/app/main/ui/ds/`:

| CLJS pattern | TypeScript equivalent |
|--------------|-----------------------|
| `mf/defc cta* {::mf/wrap [mf/memo]}` | `export const Cta = memo(CtaInner)` |
| `(stl/css :root)` | `styles.root` (CSS Module) |
| `(stl/css-case :a cond :b true)` | `clsx({ [styles.a]: cond, [styles.b]: true })` |
| `(d/append-class cls (stl/css :root))` | `clsx(styles.root, className)` |
| `[:> text* {:as "span" :typography t/headline-small}]` | `<Text as="span" typography="headline-small" />` |
| `{:keys [class title children] :rest props}` | `{ className, title, children, ...rest }` |
| `[:> "div" props ...]` | `<div className={...} {...rest}>...</div>` |

CLJS schema validation (`:map [:title :string]`) is replaced by TypeScript
`interface` / prop types — no runtime validation needed.

## Exports

Every public symbol must be re-exported from `src/index.ts`:

```ts
export { MyComponent } from './lib/category/MyComponent';
export type { MyComponentProps } from './lib/category/MyComponent';
```

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
│       ├── controls/             # Form controls
│       │   ├── Checkbox.tsx
│       │   ├── Checkbox.module.scss
│       │   ├── Checkbox.stories.tsx
│       │   ├── Checkbox.spec.tsx
│       │   ├── Input.tsx
│       │   ├── Input.module.scss
│       │   ├── Input.stories.tsx
│       │   ├── Input.spec.tsx
│       │   ├── RadioButtons.tsx
│       │   ├── RadioButtons.module.scss
│       │   ├── RadioButtons.stories.tsx
│       │   ├── RadioButtons.spec.tsx
│       │   ├── Switch.tsx
│       │   ├── Switch.module.scss
│       │   ├── Switch.stories.tsx
│       │   ├── Switch.spec.tsx
│       │   └── utilities/
│       │       ├── HintMessage.tsx
│       │       ├── HintMessage.module.scss
│       │       ├── HintMessage.stories.tsx
│       │       ├── HintMessage.spec.tsx
│       │       ├── InputField.tsx
│       │       ├── InputField.module.scss
│       │       ├── InputField.stories.tsx
│       │       ├── InputField.spec.tsx
│       │       ├── Label.tsx
│       │       ├── Label.module.scss
│       │       ├── Label.stories.tsx
│       │       └── Label.spec.tsx
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
│       ├── notifications/
│       │   └── shared/
│       │       ├── NotificationPill.tsx
│       │       ├── NotificationPill.module.scss
│       │       ├── NotificationPill.stories.tsx
│       │       └── NotificationPill.spec.tsx
│       ├── layout/               # Layout components
│       │   ├── TabSwitcher.tsx
│       │   ├── TabSwitcher.module.scss
│       │   ├── TabSwitcher.stories.tsx
│       │   └── TabSwitcher.spec.tsx
│       └── product/              # Product-level components (e.g. Cta, EmptyPlaceholder)
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
| `ds/product/panel_title.cljs` | `src/lib/product/PanelTitle.tsx` |
| `ds/buttons/button.cljs` | `src/lib/buttons/Button.tsx` |
| `ds/buttons/icon_button.cljs` | `src/lib/buttons/IconButton.tsx` |
| `ds/utilities/swatch.cljs` | `src/lib/utilities/Swatch.tsx` |
| `ds/controls/utilities/label.cljs` | `src/lib/controls/utilities/Label.tsx` |
| `ds/controls/utilities/hint_message.cljs` | `src/lib/controls/utilities/HintMessage.tsx` |
| `ds/controls/utilities/input_field.cljs` | `src/lib/controls/utilities/InputField.tsx` |
| `ds/controls/switch.cljs` | `src/lib/controls/Switch.tsx` |
| `ds/controls/checkbox.cljs` | `src/lib/controls/Checkbox.tsx` |
| `ds/controls/input.cljs` | `src/lib/controls/Input.tsx` |
| `ds/controls/radio_buttons.cljs` | `src/lib/controls/RadioButtons.tsx` |
| `ds/product/empty_placeholder.cljs` | `src/lib/product/EmptyPlaceholder.tsx` |
| `ds/notifications/shared/notification_pill.cljs` | `src/lib/notifications/shared/NotificationPill.tsx` |
| `ds/layout/tab_switcher.cljs` | `src/lib/layout/TabSwitcher.tsx` |
| `ds/product/empty_state.cljs` | `src/lib/product/EmptyState.tsx` |

### Known Tooling Notes

- **No `.babelrc` in this package.** The `react-docgen` plugin used by
  Storybook calls `@babel/core`'s `loadPartialConfig`. If a `.babelrc` is
  present with empty `presets: []` it disables the default `typescript` Babel
  plugin, causing `import type` to fail in story files. Keep the `.babelrc`
  deleted.
- **`@vitejs/plugin-react` v6** removed the `babel` option. Use
  `reactCompilerPreset()` from the same package instead of passing
  `babel: { plugins: ['babel-plugin-react-compiler'] }`.
- **`setState` inside `useEffect` is banned** by the `react-hooks/set-state-in-effect`
  ESLint rule. For uncontrolled-with-default patterns (e.g. `Switch`), initialise
  state from the prop directly and let consumers use a `key` prop to reset.
- **`disabled` is not a valid attribute on `<div>`** in TypeScript. Use
  `aria-disabled` + `data-disabled` attributes instead, and target them in SCSS
  (`[data-disabled]` selector).
- **CSS Module class names must be kebab-case** — stylelint rejects camelCase
  selectors. Use bracket notation in TSX when needed (`styles["my-class"]`).
- **`@property` CSS at-rules cannot live in CSS Modules** — they must be in a
  global (non-module) stylesheet. Create a `component-properties.scss` sidecar
  file and import it as a side effect (`import "./component-properties.scss"`)
  from the TSX file so it lands in global scope.
- **Storybook stories using `useState`** must extract the stateful wrapper into
  a named component (`function StatefulFoo(…)`) rather than using an inline
  arrow function in `render: (args) => { ... }`. The `react-hooks/rules-of-hooks`
  ESLint rule rejects hooks inside anonymous `render` callbacks.
- **Story args with required props** — when the story component has required props
  (e.g. `tabs`, `selected`), either provide them as `args` defaults in meta or
  use a wrapper component (`StatefulFoo`) as the `component` in meta to avoid
  TypeScript `args` errors on individual stories.
- **`fireEvent.change` on controlled radio/checkbox inputs** is not reliably
  dispatched through React's synthetic event system in JSDOM. Test `onChange`
  wiring structurally (e.g. check `readOnly` attribute) rather than expecting
  `fireEvent.change` to trigger the handler.

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

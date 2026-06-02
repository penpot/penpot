---
title: Design file structure and best practices
order: 4
desc: Organize Penpot pages, components, and libraries so humans and MCP-connected agents can navigate your files reliably.
---
# Design file structure and best practices

## 1. General file structure

### 1.1 Organization into boards

* One board per functional area or feature, not per screen. Example: "Onboarding," "Dashboard," "Settings."
* Use the canvas as a logical map: group frames horizontally or vertically according to flow or hierarchy (e.g., left -> wireframes, right -> final design).
* Avoid "chaotic infinite canvas": every board must have a clear purpose and a visual entry point.

## 2. Design system and tokens

### 2.1 Token hierarchy

* **Tier 1 - Global tokens:** The system base. E.g.: `color.base.neutral.100`, `spacing.base.8`.
* **Tier 2 - Semantic tokens:** Assign meaning to the global tokens. E.g.: `color.bg.default`, `color.text.primary`.
* **Tier 3 - Component tokens:** Specific to a component or pattern. E.g.: `color.button.primary.bg`.
* Maintain the relationships between tiers documented in the design system: specify which tokens can be inherited or modified.
* Use local and global variables with hierarchical names (e.g., `color.text.primary`, `radius.button.sm`).
* Avoid "hard" (manual) values for colors, typography, or spacing. Everything must originate from tokens.

## 3. Components and variants

### 3.1 Organization

* Group components by functional categories, not visual ones: Button, FormField, Card.
* Maintain semantic and consistent naming: `button/primary/default`, `button/primary/hover`, `form/input/text/focus`.
* Use variants only when the differences are part of the same pattern (do not mix distinct components under one variant).

### 3.2 Composition

* Avoid excessive nesting of frames. Maintain a visual depth of 3-4 levels maximum.
* Use layout (Flex or Grid) logically:
  * Flex for linear stacks (buttons, lists, forms).
  * Grid for predictable structures (cards, galleries, dashboards).
* Define clear constraints or stretch behaviors for every component.

## 4. Naming and semantics

### 4.1 Layers

* Name layers by function, not appearance: ❌ `rectangle 23` -> ✅ `background`, `icon`, `title`.
* Avoid duplicating context in names. If the component is named `button`, its internal layers should not start with `button-...`.

### 4.2 Hierarchy

* Use hierarchical names with `/` to group components: `form/input/text`, `form/input/checkbox`.

## 5. Layout and visual structure

### 5.1 Responsive layout

* Apply layout to the majority of containers.
* Adjust padding, spacing, and alignment from the layout panel, not with invisible rectangles.
* Avoid fixed boards except for graphic or decorative elements.

### 5.2 Grid and spacing

* Define a base spacing unit (for example, 8px) and derive all margins and paddings from it.
* Use column grids on complex pages, but flex layout for internal structures.

## 6. Accessibility and consistency

* Maintain sufficient contrast between text and background (WCAG AA minimum).
* Use typography in predefined scales (8, 10, 12, 14, 16, 20...).
* Avoid the arbitrary use of color to communicate status without textual support.

## 7. Export and collaboration

* Prepare boards with consistent names for handoff (`/screens/login`, `/components/button`).
* Use component and variable names that make sense to developers.
* Avoid duplicates: establish a single source of truth per component or style.
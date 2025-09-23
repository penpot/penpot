---
title: 3.10. UI Guide
desc: Learn UI development with React & Rumext, design system implementation, and performance considerations. See Penpot's technical guide. Free to use!
---

# UI Guide

These are the guidelines for developing UI in Penpot, including the design system.

## React & Rumext

The UI in Penpot uses React v18 , with the help of [rumext](https://github.com/funcool/rumext) for providing Clojure bindings. See [Rumext's User Guide](https://funcool.github.io/rumext/latest/user-guide.html) to learn how to create React components with Clojure.

## General guidelines

We want to hold our UI code to the same quality standards of the rest of the codebase. In practice, this means:

- UI components should be easy to maintain over time, especially because our design system is ever-changing.
- We need to apply the rules for good software design:
  - The code should adhere to common patterns.
  - UI components should offer an ergonomic "API" (i.e. props).
  - UI components should favor composability.
  - Try to have loose coupling.

### Composability

**Composability is a common pattern** in the Web. We can see it in the standard HTML elements, which are made to be nested one inside another to craft more complex content. Standard Web components also offer slots to make composability more flexible.

<mark>Our UI components must be composable</mark>. In React, this is achieved via the <code class="language-clojure">children</code> prop, in addition to pass slotted components via regular props.

#### Use of <code class="language-clojure">children</code>

> **⚠️ NOTE**: Avoid manipulating <code class="language-clojure">children</code> in your component. See [React docs](https://react.dev/reference/react/Children#alternatives) about the topic.

✅ **DO: Use children when we need to enable composing**

```clojure
(mf/defc primary-button*
  [{:keys [children] :rest props}]
  [:> "button" props children])
```

❓**Why?**

By using children, we are signaling the users of the component that they can put things _inside_, vs a regular prop that only works with text, etc. For example, it’s obvious that we can do things like this:

```clojure
[:> button* {}
  [:*
   "Subscribe for "
   [:& money-amount {:currency "EUR" amount: 3000}]]]
```

#### Use of slotted props

When we need to either:

- Inject multiple (and separate) groups of elements.
- Manipulate the provided components to add, remove, filter them, etc.

Instead of <code class="language-clojure">children</code>, we can pass the component(s) via a regular a prop.

#### When _not_ to pass a component via a prop

It's about **ownership**. By allowing the passing of a full component, the responsibility of styling and handling the events of that component belong to whoever instantiated that component and passed it to another one.

For instance, here the user would be in total control of the <code class="language-clojure">icon</code> component for styling (and for choosing which component to use as an icon, be it another React component, or a plain SVG, etc.)

```clojure
(mf/defc button*
  [{:keys [icon children] :rest props}]
  [:> "button" props
     icon
     children])
```

However, we might want to control the aspect of the icons, or limit which icons are available for this component, or choose which specific React component should be used. In this case, instead of passing the component via a prop, we'd want to provide the data we need for the icon component to be instantiated:

```clojure
(mf/defc button*
  [{:keys [icon children] :rest props}]
  (assert (or (nil? icon) (contains? valid-icon-list icon) "expected valid icon id"))
  [:> "button" props
    (when icon [:> icon* {:icon-id icon :size "m"}])
    children])
```

### Our components should have a clear responsibility

It's important we are aware of:

- What are the **boundaries** of our component (i.e. what it can and cannot do)
  - Like in regular programming, it's good to keep all the inner elements at the same level of abstraction.
  - If a component grows too big, we can split it in several ones. Note that we can mark components as private with the <code class="language-clojure">::mf/private true</code> meta tag.
- Which component is **responsible for what**.

As a rule of thumb:

- Components own the stuff they instantiate themselves.
- Slotted components or <code class="language-clojure">children</code> belong to the place they have been instantiated.

This ownership materializes in other areas, like **styles**. For instance, parent components are usually reponsible for placing their children into a layout. Or, as mentioned earlier, we should avoid manipulating the styles of a component we don't have ownership over.

## Styling components

We use **CSS modules** and **Sass** to style components. Use the <code class="language-clojure">(stl/css)</code> and <code class="language-clojure">(stl/css-case)</code> functions to generate the class names for the CSS modules.

### Allow passing a class name

Our components should allow some customization by whoever is instantiating them. This is useful for positioning elements in a layout, providing CSS properties, etc.

This is achieved by accepting a <code class="language-clojure">class</code> prop (equivalent to <code class="language-clojure">className</code> in JSX). Then, we need to join the class name we have received as a prop with our own class name for CSS modules.

```clojure
(mf/defc button*
  [{:keys [children class] :rest props}]
  (let [class (dm/str class " " (stl/css :primary-button))
    props (mf/spread-props props {:class class})]
    [:> "button" props children]))
```

### About nested selectors

Nested styles for DOM elements that are not instantiated by our component should be avoided. Otherwise, we would be leaking CSS out of the component scope, which can lead to hard-to-maintain code.

❌ **AVOID: Styling elements that don’t belong to the component**

```clojure
(mf/defc button*
  [{:keys [children] :rest props}]
  (let  [props (mf/spread-props props {:class (stl/css :primary-button)})]
    ;; note that we are NOT instantiating a <svg> here.
    [:> "button" props children]))

;; later in code
[:> button* {}
  [:> icon {:id "foo"}]
  "Lorem ipsum"]
```

```scss
.button {
  // ...
  svg {
    fill: var(--icon-color);
  }
}
```

✅ **DO: Take ownership of instantiating the component we need to style**

```clojure
(mf/defc button*
  [{:keys [icon children class] :rest props}]
  (let [props (mf/spread-props props {:class (stl/css :button)})]
    [:> "button" props
     (when icon [:> icon* {:icon-id icon :size "m" :class (stl/css :icon)}])
     [:span {:class (stl/css :label-wrapper)} children]]))

;; later in code
[:> button* {:icon "foo"} "Lorem ipsum"]
```

```scss
.button {
  // ...
}

.icon {
  fill: var(--icon-color);
}
```

### Favor lower specificity

This helps with maintanibility, since lower [specificity](https://developer.mozilla.org/en-US/docs/Web/CSS/Specificity) styles are easier to override.

Remember that nesting selector increases specificity, and it's usually not needed. However, pseudo-classes and pseudo-elements don't.

❌ **AVOID: Using a not-needed high specificity**

```scss
.btn {
  // ...
  .icon {
    fill: var(--icon-color);
  }
}
```

✅ **DO: Choose selectors with low specificity**

```scss
.btn {
  // ...
}

.icon {
  fill: var(--icon-color);
}
```
Note: Thanks to CSS Modules, identical class names defined in different files are scoped locally and do not cause naming collisions.

### Use CSS logical properties

The [logical properties](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_logical_properties_and_values) define styles relative to the content’s writing mode (e.g., inline, block) instead of physical directions (left, right, etc). This improves support for right-to-left (RTL) languages and enhances layout flexibility.

❌ **AVOID: Physical properties**

```scss
.btn {
  padding-left: var(--sp-xs);
}
```

✅ **DO: Use direction‐relative equivalents**

```scss
.btn {
  padding-inline-start: var(--sp-xs);
}
```

Note: Although `width` and `height` are physical properties, their use is allowed in CSS files. They remain more readable and intuitive than their logical counterparts (`inline-size`, `block-size`) in many contexts. Since our layouts are not vertically-sensitive, we don't gain practical benefits from using logical properties here.

### Use named DS variables

Avoid hardcoded values like `px`, `rem`, or raw SASS variables `($s-*)`. Use semantic, named variables provided by the Design System to ensure consistency and scalability.

#### Spacing (margins, paddings, gaps...)
Use variables from `frontend/src/app/main/ui/ds/spacing.scss`. These are predefined and approved by the design team — **do not add or modify values without design approval**.

#### Fixed dimensions
For fixed dimensions (e.g., modals' widths) defined by design and not layout-driven, use or define variables in `frontend/src/app/main/ui/ds/_sizes.scss`. To use them:

```scss
@use "ds/_sizes.scss" as *;
```
Note: Since these values haven't been semantically defined yet, we’re temporarily using SASS variables instead of named CSS custom properties.

#### Border Widths
Use border thickness variables from `frontend/src/app/main/ui/ds/_borders.scss`. To import:

```scss
@use "ds/_borders.scss" as *;
```

Avoid using sass variables defined on `frontend/resources/styles/common/refactor/spacing.scss` that are deprecated.

❌ **AVOID: Using sass unnamed variables or hardcoded values**

```scss
.btn {
  padding: $s-24;
}

.icon {
  width: 16px;
}
```

✅ **DO: Use DS variables**

```scss
.btn {
  padding: var(--sp-xl);
}

.icon {
  width: var(--sp-l);
}
```

### Use Proper Typography Components

Replace plain text tags with `text*` or `heading*` components from the Design System to ensure visual consistency and accessibility.

❌ **AVOID: Using text wrappers**

```clojure
  [:h2 {:class (stl/css :modal-title)} title]
  [:div {:class (stl/css :modal-content)}
  "Content"]
```

✅ **DO: Use spacing named variables**

```clojure
...
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]] 
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
...

  [:> heading* {:level 2
                :typography t/headline-medium
                :class (stl/css :modal-title)} 
    title]
  [:> text* {:as "div" 
             :typography t/body-medium  
             :class (stl/css :modal-content)}
    "Content"]
```

When applying typography in SCSS, use the proper mixin from the Design System.

❌ **AVOID: Deprecated mixins**

```scss
.class {
  @include headlineLargeTypography;
}
```

✅ **DO: Use the DS mixin**
```scss
@use "ds/typography.scss" as t;

.class {
  @include t.use-typography("body-small");
}
```
You can find the full list of available typography tokens in [Storybook](https://design.penpot.app/storybook/?path=/docs/foundations-typography--docs).
If the design you are implementing doesn't match any of them, ask a designer.


### Use custom properties within components

Reduce the need for one-off SASS variables by leveraging [CSS custom properties](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_cascading_variables/Using_CSS_custom_properties) in your component styles. This keeps component theming flexible and composable.

For instance, this is how we handle the styles of <code class="language-clojure">\<Toast></code>, which have a different style depending on the level of the message (default, info, error, etc.)

```scss
.toast {
  // common styles for all toasts
  // ...

  --toast-bg-color: var(--color-background-primary);
  --toast-icon-color: var(--color-foreground-secondary);
  // ... more variables here

  background-color: var(--toast-bg-color);
}

.toast-icon {
  color: var(--toast-bg-color);
}

.toast-info {
  --toast-bg-color: var(--color-background-info);
  --toast-icon-color: var(--color-accent-info);
  // ... override more variables here
}

.toast-error {
  --toast-bg-color: var(--color-background-error);
  --toast-icon-color: var(--color-accent-error);
  // ... override more variables here
}

// ... more variants here
```

## Semantics and accessibility

All UI code must be accessible. Ensure that your components are designed to be usable by people with a wide range of abilities and disabilities.

### Let the browser do the heavy lifting

When developing UI components in Penpot, we believe it is crucial to ensure that our frontend code is semantic and follows HTML conventions. Semantic HTML helps improve the readability and accessibility of Penpot. Use appropriate HTML tags to define the structure and purpose of your content. This not only enhances the user experience but also ensures better accessibility.

Whenever possible, leverage HTML semantic elements, which have been implemented by browsers and are accessible out of the box.

This includes:

- Using <code class="language-html">\<a></code> for link (navigation, downloading files, sending e-mails via <code class="language-html">mailto:</code>, etc.)
- Using <code class="language-html">\<button></code> for triggering actions (submitting a form, closing a modal, selecting a tool, etc.)
- Using the proper heading level.
- Etc.

Also, elements **should be focusable** with keyboard. Pay attention to <code class="language-html">tabindex</code> and the use of focus.

### Aria roles

If you cannot use a native element because of styling (like a <code class="language-html">\<select></code> for a dropdown menu), consider either adding one that is hidden (except for assistive software) or use relevant [aria roles](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Roles) in your custom markup.

When using images as icons, they should have an <code class="language-html">aria-label</code>, <code class="language-html">alt</code>, or similar if they are not decorative and there's no text around to tag the button. Think, for instance, of a generic toolbar without text labels, just icon buttons.

For decorative images, they don't need to be anounced to assistive devices and should have <code class="language-html">aria-hidden</code> set to <code class="language-html">true</code>.

### Component patterns

When a component follows a pattern set in the WCAG patterns page, we adhere to the rules and guidelines specified there. This ensures consistency and compliance with accessibility standards. See more at [Patterns | APG | WAI | W3C](https://www.w3.org/WAI/ARIA/apg/patterns/)

## Clojure / Rumext implementation notes

Please refer to the [Rumext User Guide](https://funcool.github.io/rumext/latest/user-guide.html) for important information, like naming conventions, available functions and macros, transformations done to props, etc.

Some things to have in mind:

- Avoid using <code class="language-clojure">?</code> for boolean props, since they don't get a clean translation to JavaScript.
- You can use type hints such as <code class="language-clojure">^boolean</code> to get JS semantics.
- Split big components into smaller ones. You can mark components as private with the <code class="language-clojure">::mf/private true</code> meta.

### Delegating props

There is a mechanism to [delegate props](https://react.dev/learn/passing-props-to-a-component#forwarding-props-with-the-jsx-spread-syntax) equivalent to this:

```jsx
const Button => ({children, ...other}) {
  return <button {...other}>{children}</button>
};
```

We just need to use `:rest ` when declaring the component props.

```clojure
(mf/defc button*
  [{:keys [children] :rest other}]
  [:> "button" other children])
```

If we need to augment this props object, we can use <code class="language-clojure">spread-props</code> and the usual transformations that Rumext does (like <code class="language-clojure">class</code> -> <code class="language-clojure">className</code>, for instance) will be applied too.

```clojure
(mf/defc button*
  [{:keys [children class] :rest props}]
  (let [class (dm/str class " " (stl/css :button))
        props (mf/spread-props props {:class class})]
    [:> "button" props children]))
```

### Performance considerations

For components that belong to the “hot path” of rendering (like those in the sidebar, for instance), it’s worth avoiding some pitfalls that make rendering slower and/or will trigger a re-render.

Most of this techniques revolve around achieving one of these:

- Avoid creating brand new objects and functions in each render.
- Avoid needlessly operations that can be costly.
- Avoid a re-render.

#### Use of a JS object as props

It's faster to use a JS Object for props instead of a native Clojure map, because then that conversion will not happen in runtime in each re-render.

✅ **DO: Use the metadata <code class="language-clojure">::mf/props :obj</code> when creating a component**

```clojure
(mf/defc icon*
  [props]
  ;; ...
  )
```

#### Split large and complex components into smaller parts

This can help to avoid full re-renders.

#### Avoid creating anonymous functions as callback handlers, etc.

This creates a brand new function every render. Instead, create the function on its own and memoize it when needed.

❌ **AVOID: Creating anonymous functions for handlers**

```clojure
(mf/defc login-button {::mf/props obj} []
  [:button {:on-click (fn []
    ;; emit event to login, etc.
    )}
   "Login"])
```

✅ **DO: Use named functions as callback handlers**

```clojure
(defn- login []
 ;; ...
 )

(mf/defc login-button
  []
  [:button {:on-click login} "Login"])

```

#### Avoid defining functions inside of a component (via <code class="language-clojure">let</code>)

When we do this inside of a component, a brand new function is created in every render.

❌ \*\*AVOID: Using <code class="language-clojure">let</code> to define functions

```clojure
(mf/defc login-button
  []
  (let [click-handler (fn []
                       ;; ...
                       )]
    [:button {:on-click click-handler} "Login"]))
```

✅ **DO: Define functions outside of the component**

```clojure
(defn- login []
 ;; ...
 )

(mf/defc login-button
  []
  [:button {:on-click login} "Login"])
```

#### Avoid defining functions with <code class="language-clojure">partial</code> inside of components

<code class="language-clojure">partial</code> returns a brand new anonymous function, so we should avoid using it in each render. For callback handlers that need parameters, a work around is to store these as <code class="language-clojure">data-\*</code> attributes and retrieve them inside the function.

❌ **AVOID: Using `partial` inside of a component**

```clojure
(defn- set-margin [side value]
  ;; ...
  )

(mf/defc margins []
  [:*
    [:> numeric-input* {:on-change (partial set-margin :left)}]
    [:> numeric-input* {:on-change (partial set-margin :right)}] ])
```

✅ **DO: Use <code class="language-clojure">data-\*</code> attributes to modify a function (many uses)**

```clojure
(defn- set-margin [value event]
  (let [side -> (dom/get-current-target event)
                (dom/get-data "side")
                (keyword)]
    ;; ...
)

(defc margins []
  [:*
    [:> numeric-input* {:data-side "left" :on-change set-margin}]
    [:> numeric-input* {:data-side "right" :on-change set-margin}]
    [:> numeric-input* {:data-side "top" :on-change set-margin}]
    [:> numeric-input* {:data-side "bottom" :on-change set-margin}]])

```

✅ **DO: Store the returned function from <code class="language-clojure">partial</code> (few uses)**

```clojure
(defn- set-padding [sides value]
  ;; ...
  )

(def set-block-padding (partial set-padding :block))
(def set-inline-padding (partial set-padding :inline))

(defc paddings []
  [:*
    [:> numeric-input* {:on-change set-block-padding}]
    [:> numeric-input* {:on-change set-inline-padding}]])
```

#### Store values you need to use multiple times

Often we need to access values from props. It's best if we destructure them (because it can be costly, especially if this adds up and we need to access them multiple times) and store them in variables.

##### Destructuring props

✅ **DO: Destructure props with <code class="language-clojure">:keys</code>**

```clojure
(defc icon
  [{:keys [size img] :as props]
  [:svg {:width size
         :height size
         :class (stl/css-case icon true
                              icon-large (> size 16))}
    [:use {:href img}]])
```

❌ **AVOID: Accessing the object each time**

```clojure
(defc icon
  [props]
  [:svg {:width (unchecked-get props "size")
         :height (unchecked-get props "size")
         :class (stl/css-case icon true
                              icon-large (> (unchecked-get props "size") 16))}
    [:use {:href (unchecked-get props "img")}]])
```

##### Storing state values

We can avoid multiple calls to <code class="language-clojure">(deref)</code> if we store the value in a variable.

✅ **DO: store state values**

```clojure
(defc accordion
  [{:keys [^boolean default-open title children] :as props]

  (let [
    open* (mf/use-state default-open)
    open? (deref open*)]
    [:details {:open open?}
      [:summary title]
      children]))
```

##### Unroll loops

Creating an array of static elements and iterating over it to generate DOM may be more costly than manually unrolling the loop.

❌ **AVOID: iterating over a static array**

```clojure
(defc shape-toolbar []
  (let tools ["rect" "circle" "text"]
    (for tool tools [:> tool-button {:tool tool}])))
```

✅ **DO: unroll the loop**

```clojure
(defc shape-toolbar []
  [:*
    [:> tool-button {:tool "rect"}]
    [:> tool-button {:tool "circle"}]
    [:> tool-button {:tool "text"}]])
```

## Penpot Design System

Penpot has started to use a **design system**, which is located at <code class="language-bash">frontend/src/app/main/ui/ds</code>. The components of the design system is published in a Storybook at [hourly.penpot.dev/storybook/](https://hourly.penpot.dev/storybook/) with the contents of the <code class="language-bash">develop</code> branch of the repository.

<mark>When a UI component is **available in the design system**, use it!</mark>. If it's not available but it's part of the Design System (ask the design folks if you are unsure), then do add it to the design system and Storybook.

### Adding a new component

In order to implement a new component for the design system, you need to:

- Add a new <code class="language-bash">\<component>.cljs</code> file within the <code class="language-bash">ds/</code> folder tree. This contains the CLJS implementation of the component, and related code (props schemas, private components, etc.).
- Add a <code class="language-bash">\<component>.css</code> file with the styles for the component. This is a CSS Module file, and the selectors are scoped to this component.
- Add a <code class="language-bash">\<component>.stories.jsx</code> Storybook file (see the _Storybook_ section below).
- (Optional) When available docs, add a <code class="language-bash">\<component>.mdx</code> doc file (see _Storybook_ section below).

In addition to the above, you also need to **specifically export the new component** with a JavaScript-friendly name in <code class="language-bash">frontend/src/app/main/ui/ds.cljs</code>.

### Tokens

We use three **levels of tokens**:

- **Primary** tokens, referring to raw values (i.e. pixels, hex colors, etc.) of color, sizes, borders, etc. These are implemented as Sass variables. Examples are: <code class="language-css">$mint-250</code>, <code class="language-css">$sz-16</code>, <code class="language-css">$br-circle</code>, etc.

- **Semantic** tokens, used mainly for theming. These are implemented with **CSS custom properties**. Depending on the theme, these semantic tokens would have different primary tokens as values. For instance, <code class="language-css">--color-accent-primary</code> is <code class="language-css">$purple-700</code> when the light theme is active, but <code class="language-css">$mint-150</code> in the default theme. These custom properties have **global scope**.

- **Component** tokens, defined at component level as **CSS custom properties**. These are very useful when implementing variants. Examples include <code class="language-css">--button-bg-color</code> or <code class="language-css">--toast-icon-color</code>. These custom properties are constrained to the **local scope** of its component.

### Implementing variants

We can leverage component tokens to easily implement variants as explained [here](/technical-guide/developer/ui/#use-custom-properties-within-components).


### Using icons and SVG assets

Please refer to the Storybook [documentation for icons](https://hourly.penpot.dev/storybook/?path=/docs/foundations-assets-icon--docs) and other [SVG assets](https://hourly.penpot.dev/storybook/?path=/docs/foundations-assets-rawsvg--docs) (logos, illustrations, etc.).

### Storybook

We use [Storybook](https://storybook.js.org/) to implement and showcase the components of the Design System.

The Storybook is available at the <code class="language-bash">/storybook</code> path in the URL for each environment. For instance, the one built out of our <code class="language-bash">develop</code> branch is available at [hourly.penpot.dev/storybook](https://hourly.penpot.dev/storybook).

#### Local development

Use <code class="language-bash">yarn watch:storybook</code> to develop the Design System components with the help of Storybook.

> **⚠️ WARNING**: Do stop any existing Shadow CLJS and asset compilation jobs (like the ones running at tabs <code class="language-bash">0</code> and <code class="language-bash">1</code> in the devenv tmux), because <code class="language-bash">watch:storybook</code> will spawn their own.

#### Writing stories

You should add a Storybook file for each design system component you implement. This is a <code class="language-bash">.jsx</code> file located at the same place as your component file, with the same name. For instance, a component defined in <code class="language-bash">loader.cljs</code> should have a <code class="language-bash">loader.stories.jsx</code> files alongside.

A **story showcases how to use** a component. For the most relevant props of your component, it's important to have at least one story to show how it's used and what effect it has.

Things to take into account when considering which stories to add and how to write them:

- Stories show have a <code class="language-bash">Default</code> story that showcases how the component looks like with default values for all the props.

- If a component has variants, we should show each one in its own story.

- Leverage setting base prop values in <code class="language-bash">args</code> and common rendering code in <code class="language-bash">render</code> to reuse those in the stories and avoid code duplication.

For instance, the stories file for the <code class="language-bash">button\*</code> component looks like this:

```jsx
// ...

export default {
  title: "Buttons/Button",
  component: Components.Button,
  // These are the props of the component, and we set here default values for
  // all stories.
  args: {
    children: "Lorem ipsum",
    disabled: false,
    variant: undefined,
  },
  // ...
  render: ({ ...args }) => <Button {...args} />,
};

export const Default = {};

// An important prop: `icon`
export const WithIcon = {
  args: {
    icon: "effects",
  },
};

// A variant
export const Primary = {
  args: {
    variant: "primary",
  },
};

// Another variant
export const Secondary = {
  args: {
    variant: "secondary",
  },
};

// More variants here…
```

In addition to the above, please **use the [Controls addon](https://storybook.js.org/docs/essentials/controls)** to let users change props and see their effect on the fly.

Controls are customized with <code class="language-bash">argTypes</code>, and you can control which ones to show / hide with <code class="language-bash">parameters.controls.exclude</code>. For instance, for the <code class="language-bash">button\*</code> stories file, its relevant control-related code looks like this:

```jsx
// ...
const { icons } = Components.meta;

export default {
  // ...
  argTypes: {
    // Use the `icons` array for possible values for the `icon` prop, and
    // display them in a dropdown select
    icon: {
      options: icons,
      control: { type: "select" },
    },
    // Use a toggle for the `disabled` flag prop
    disabled: { control: "boolean" },
    // Show these values in a dropdown for the `variant` prop.
    variant: {
      options: ["primary", "secondary", "ghost", "destructive"],
      control: { type: "select" },
    },
  },
  parameters: {
    // Always hide the `children` controls.
    controls: { exclude: ["children"] },
  },
  // ...
};
```

#### Adding docs

Often, Design System components come along extra documentation provided by Design. Furthermore, they might be technical things to be aware of. For this, you can add documentation in [MDX format](https://storybook.js.org/docs/writing-docs/mdx).

You can use Storybook's <code class="language-bash">\<Canvas></code> element to showcase specific stories to enrich the documentation.

When including codeblocks, please add code in Clojure syntax (not JSX).

You can find an example MDX file in the [Buttons docs](https://hourly.penpot.dev/storybook/?path=/docs/buttons-docs--docs).

### Replacing a deprecated component

#### Run visual regression tests

We need to generate the screenshots for the visual regression tests _before_ making
any changes, so we can compare the "before substitution" and "after substitution" states.

Execute the tests in the playwright's <code class="language-bash">ds</code> project. In order to do so, stop the Shadow CLJS compiler in tmux tab <code class="language-bash">#1</code> and run;

```bash
clojure -M:dev:shadow-cljs release main
```

This will package the frontend in release mode so the tests run faster.

In your terminal, in the frontend folder, run:

```bash
npx playwright test --ui --project=ds
```

This will open the test runner UI in the selected project.

![Playwright UI](/img/tech-guide/playwright-projects.webp)

The first time you run these tests they'll fail because there are no screenshots yet, but the second time, they should pass.

#### Import the new component

In the selected file add the new namespace from the <code class="language-bash">ds</code> folder in alphabetical order:

```clojure
[app.main.ui.ds.tab-switcher :refer [tab-switcher*]]
...

[:> tab-switcher* {}]
```

> **⚠️ NOTE**: Components with a <code class="language-bash">\*</code> suffix are meant to be used with the <code class="language-clojure">[:></code> handler.

<small>Please refer to [Rumext User Guide](https://funcool.github.io/rumext/latest/user-guide.html) for more information.</small>

#### Pass props to the component

Check the props schema in the component’s source file

```clojure
(def ^:private schema:tab-switcher
  [:map
   [:class {:optional true} :string]
   [:action-button-position {:optional true}
    [:enum "start" "end"]]
   [:default-selected {:optional true} :string]
   [:tabs [:vector {:min 1} schema:tab]]])


(mf/defc tab-switcher*
  {::mf/props :obj
   ::mf/schema schema:tab-switcher}...)
```

This schema shows which props are required and which are optional, so you can
include the necessary values with the correct types.

Populate the component with the required props.

```clojure
(let [tabs
        #js [#js {:label (tr "inspect.tabs.info")
                  :id "info"
                  :content info-content}

             #js {:label (tr "inspect.tabs.code")
                  :data-testid "code"
                  :id "code"
                  :content code-content}]]

  [:> tab-switcher* {:tabs tabs
                     :default-selected "info"
                     :on-change-tab handle-change-tab
                     :class (stl/css :viewer-tab-switcher)}])
```

Once the component is rendering correctly, remove the old component and its imports.

#### Check tests after changes

Verify that everything looks the same after making the changes. To do this, run
the visual tests again as previously described.

If the design hasn’t changed, the tests should pass without issues.

However, there are cases where the design might have changed from the original.
In this case, first check the <code class="language-bash">diff</code> files provided by the test runner to ensure
that the differences are expected (e.g., positioning, size, etc.).

Once confirmed, inform the QA team about these changes so they can review and take any necessary actions.

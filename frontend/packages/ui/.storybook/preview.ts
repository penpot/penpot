// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { withThemeByClassName } from "@storybook/addon-themes";

// Design-system CSS: defines --color-*, --sp-*, fonts, resets, etc.
import "../../../resources/public/css/ds.css";

export const decorators = [
  withThemeByClassName({
    themes: {
      light: "light",
      dark: "default",
    },
    defaultTheme: "dark",
    parentSelector: "body",
  }),
];

/** @type { import('@storybook/react-vite').Preview } */
const preview = {
  decorators,

  parameters: {
    controls: {
      disableSaveFromUI: true,
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    backgrounds: {
      options: {
        theme: { name: "theme", value: "var(--color-background-secondary)" },
      },
    },
  },

  initialGlobals: {
    backgrounds: {
      value: "theme",
    },
  },
};

export default preview;

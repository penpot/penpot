import { withThemeByClassName } from "@storybook/addon-themes";

import '../resources/public/css/ds.css';

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
  decorators: decorators,

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
        theme: { name: 'theme', value: 'var(--color-background-secondary)' }
      }
    },
  },

  initialGlobals: {
    backgrounds: {
      value: "theme"
    }
  }
};

export default preview;

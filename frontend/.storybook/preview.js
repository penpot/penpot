import { withThemeByClassName } from "@storybook/addon-themes";

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

/** @type { import('@storybook/react').Preview } */
const preview = {
  decorators: decorators,
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    backgrounds: { disable: true },
  },
};

export default preview;

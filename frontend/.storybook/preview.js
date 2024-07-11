/** @type { import('@storybook/react').Preview } */
const preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
    backgrounds: {
      default: "dark",
      values: [
        {
          name: "dark",
          value: "#18181a",
        },
        {
          name: "light",
          value: "#fff",
        },
        {
          name: "debug",
          value: "#ccc",
        },
      ],
    },
  },
};

export default preview;

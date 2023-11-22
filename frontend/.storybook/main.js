/** @type { import('@storybook/react-vite').StorybookConfig } */
const config = {
  stories: ["../target/storybook/*-stories.js"],
  addons: [
    "@storybook/addon-links",
    "@storybook/addon-essentials",
    "@storybook/addon-onboarding",
    "@storybook/addon-interactions",
  ],
  features: {
    storyStoreV7: false,
  },
  framework: {
    name: "@storybook/react-vite",
    options: {},
  },
  docs: {
    autodocs: "tag",
  },
};
export default config;

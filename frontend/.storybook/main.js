/** @type { import('@storybook/react-vite').StorybookConfig } */
const config = {
  stories: ["../src/**/*.mdx", "../src/**/*.stories.@(js|jsx|mjs|ts|tsx)"],
  staticDirs: ["../resources/public"],
  addons: ["@storybook/addon-essentials", "@storybook/addon-themes"],
  core: {
    builder: "@storybook/builder-vite",
    options: {
      viteConfigPath: "../vite.config.js",
    },
  },
  framework: {
    name: "@storybook/react-vite",
    options: {},
  },
  docs: {},
};
export default config;

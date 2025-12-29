import { defineConfig } from 'vite';

/** @type { import('@storybook/react-vite').StorybookConfig } */
const config = {
  stories: ["../src/**/*.mdx", "../src/**/*.stories.@(js|jsx|mjs|ts|tsx)"],
  staticDirs: ["../resources/public"],
  addons: [
    "@storybook/addon-themes",
    "@storybook/addon-docs",
    "@storybook/addon-vitest",
  ],
  framework: {
    name: "@storybook/react-vite",
    options: {
      // fastRefresh: false,
    }
  },
  docs: {},

  async viteFinal(config) {
    return defineConfig({
      ...config,
      plugins: [
        ...(config.plugins ?? []),
        {
          name: 'force-full-reload-always',
          apply: 'serve',
          enforce: 'post',

          handleHotUpdate(ctx) {
            ctx.server.ws.send({
              type: 'full-reload',
              path: '*',
            });

            // returning [] tells Vite: “no modules handled”
            return [];
          },
        }
      ]
    });
  }
};
export default config;

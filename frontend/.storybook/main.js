import { defineConfig } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

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
  core: {
    allowedHosts: ['all'],
  },

  async viteFinal(config) {
    return defineConfig({
      ...config,
      resolve: {
        ...config.resolve,
        alias: {
          ...config.resolve?.alias,
          '@penpot/ui': path.resolve(__dirname, '../packages/ui/dist/index.js'),
        },
      },
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

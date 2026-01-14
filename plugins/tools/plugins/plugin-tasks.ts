import { CreateNodesV2, readJsonFile, logger } from '@nx/devkit';
import { createNodesFromFiles } from '@nx/devkit';
import { dirname } from 'path';

export const createNodesV2: CreateNodesV2 = [
  '**/project.json',
  async (configFiles, options, context) => {
    return await createNodesFromFiles(
      (configFile) => {
        const projectConfiguration = readJsonFile(configFile);

        if (
          !projectConfiguration.tags ||
          !projectConfiguration?.tags.includes('type:plugin') ||
          !projectConfiguration?.targets.build
        ) {
          return {};
        }

        const projectRoot = dirname(configFile);

        return {
          projects: {
            [projectRoot]: {
              root: projectRoot,
              targets: {
                init: {
                  executor: 'nx:run-commands',
                  options: {
                    command: `nx run-many --parallel --targets=buildPlugin,serve --projects=${projectConfiguration.name} --watch`,
                  },
                },
                buildPlugin: {
                  executor: '@nx/esbuild:esbuild',
                  outputs: ['{options.outputPath}'],
                  options: {
                    minify: true,
                    outputPath: `${projectConfiguration.sourceRoot}/assets/`,
                    main: `${projectConfiguration.sourceRoot}/plugin.ts`,
                    tsConfig: `${projectRoot}/tsconfig.plugin.json`,
                    generatePackageJson: false,
                    format: ['esm'],
                    deleteOutputPath: false,
                  },
                },
              },
            },
          },
        };
      },
      configFiles,
      options,
      context,
    );
  },
];

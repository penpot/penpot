import baseConfig from '../../eslint.config.js';
import jsoncParser from 'jsonc-eslint-parser';
import globals from 'globals';

export default [
  ...baseConfig,
  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {},
    languageOptions: {
      parserOptions: {
        project: './tsconfig.*?.json',
        tsconfigRootDir: import.meta.dirname,
      },
      globals: {
        ...globals.browser,
        PluginConfig: 'readonly',
      },
    },
  },
  {
    files: ['**/*.ts', '**/*.tsx'],
    rules: {},
  },
  {
    files: ['**/*.js', '**/*.jsx'],
    rules: {},
  },
  {
    files: ['*.json'],
    languageOptions: {
      parser: jsoncParser,
    },
    rules: {
      '@nx/dependency-checks': [
        'error',
        {
          ignoredFiles: [
            'libs/plugins-runtime/vite.config.ts',
            'libs/plugins-runtime/eslint.config.js',
            'libs/plugins-runtime/**/*.spec.ts',
          ],
        },
      ],
    },
  },
];

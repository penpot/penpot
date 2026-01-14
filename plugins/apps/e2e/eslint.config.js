import baseConfig from '../../eslint.config.js';
import typescriptEslintParser from '@typescript-eslint/parser';
import globals from 'globals';

export default [
  ...baseConfig,
  {
    languageOptions: {
      parser: typescriptEslintParser,
      parserOptions: { project: './apps/e2e/tsconfig.json' },
    },
  },
  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {},
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
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
  { ignores: ['vite.config.ts'] },
];

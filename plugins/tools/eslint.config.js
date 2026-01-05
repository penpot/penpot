import baseConfig from '../../eslint.config.js';
import jsoncParser from 'jsonc-eslint-parser';
import globals from 'globals';

export default [
  ...baseConfig,
  {
    files: ['**/*.ts'],
    rules: {},
    languageOptions: {
      parserOptions: {
        project: './tsconfig.*?.json',
        tsconfigRootDir: import.meta.dirname,
      },
      globals: {
        ...globals.node,
        PluginConfig: 'readonly',
      },
    },
  },
];

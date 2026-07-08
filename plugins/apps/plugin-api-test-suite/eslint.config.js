import baseConfig from '../../eslint.config.js';

export default [
  ...baseConfig,
  {
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: {
      parserOptions: {
        project: './tsconfig.*?.json',
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {},
  },
  {
    ignores: [
      '**/assets/*.js',
      'vite.config.ts',
      'vite.config.headless.ts',
      'vite.config.tests.ts',
      'vite.config.iife.ts',
    ],
  },
];

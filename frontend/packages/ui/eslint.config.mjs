// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import tseslint from "typescript-eslint";
import pluginReact from "eslint-plugin-react";
import pluginReactHooks from "eslint-plugin-react-hooks";
import pluginJsxA11y from "eslint-plugin-jsx-a11y";
import pluginImport from "eslint-plugin-import";
import globals from "globals";

/** @type {import("eslint").Linter.Config[]} */
export default [
  {
    ignores: ["dist/**", "node_modules/**", ".storybook/**"],
  },

  // TypeScript + TSX source files
  ...tseslint.config({
    files: ["src/**/*.{ts,tsx}"],
    extends: [
      tseslint.configs.recommended,
      pluginReact.configs.flat.recommended,
    ],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: {
      "react-hooks": pluginReactHooks,
      "jsx-a11y": pluginJsxA11y,
      import: pluginImport,
    },
    settings: {
      react: { version: "detect" },
    },
    rules: {
      // React
      "react/react-in-jsx-scope": "off", // Not needed with React 17+ JSX transform
      "react/prop-types": "off", // TypeScript handles prop validation
      "react/display-name": "off", // memo-wrapped inner functions don't need display names

      // React Hooks
      ...pluginReactHooks.configs["recommended-latest"].rules,

      // Imports
      "import/no-duplicates": "error",
      "import/no-unresolved": "off", // TypeScript handles resolution
      "import/first": "error",

      // TypeScript — relax rules that conflict with common patterns
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],

      // General
      "no-console": ["warn", { allow: ["warn", "error"] }],
      "prefer-const": "error",
      "no-var": "error",
    },
  }),

  // Vitest spec files — add test globals
  ...tseslint.config({
    files: ["src/**/*.spec.{ts,tsx}"],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2022,
        describe: "readonly",
        it: "readonly",
        expect: "readonly",
        beforeEach: "readonly",
        afterEach: "readonly",
        beforeAll: "readonly",
        afterAll: "readonly",
        vi: "readonly",
      },
    },
  }),

  // Storybook story files — relax some rules
  ...tseslint.config({
    files: ["src/**/*.stories.{ts,tsx}"],
    rules: {
      "import/first": "off",
      "@typescript-eslint/no-unused-vars": "off",
    },
  }),
];

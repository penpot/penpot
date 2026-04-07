// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import postcssScss from "postcss-scss";

/** @type {import("stylelint").Config} */
export default {
  extends: ["stylelint-config-standard-scss"],
  plugins: ["stylelint-scss", "stylelint-use-logical-spec"],
  overrides: [
    {
      files: ["**/*.scss"],
      customSyntax: postcssScss,
    },
  ],
  rules: {
    "at-rule-no-unknown": null,
    "declaration-property-value-no-unknown": null,
    "selector-pseudo-class-no-unknown": [
      true,
      { ignorePseudoClasses: ["global"] },
    ],

    // scss
    "scss/comment-no-empty": null,
    "scss/at-rule-no-unknown": true,
    // TODO: this rule should be enabled to follow scss conventions
    "scss/load-no-partial-leading-underscore": null,
    // This allows using the characters - or _ as a prefix and is ISO compliant with the Sass specification.
    "scss/dollar-variable-pattern": "^[-_]?([a-z][a-z0-9]*)(-[a-z0-9]+)*$",
    // This allows using the characters - or _ as a prefix and is ISO compliant with the Sass specification.
    "scss/at-mixin-pattern": "^[-_]?([a-z][a-z0-9]*)(-[a-z0-9]+)*$",
  },
};

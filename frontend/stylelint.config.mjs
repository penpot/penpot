import postcssScss from "postcss-scss";

/** @type {import("stylelint").Config} */
export default {
  extends: ["stylelint-config-standard"],
  plugins: ["stylelint-scss", "stylelint-use-logical-spec"],
  overrides: [
    {
      files: ["**/*.scss"],
      customSyntax: postcssScss,
    },
  ],
  rules: {
    "at-rule-no-unknown": null,
    "scss/at-rule-no-unknown": true,
    "selector-pseudo-element-no-unknown": true,
    // Using quotes
    "font-family-name-quotes": "always-unless-keyword",
    "function-url-quotes": "always",
    "selector-attribute-quotes": "always",
    // Disallow vendor prefixes
    "at-rule-no-vendor-prefix": true,
    "media-feature-name-no-vendor-prefix": true,
    "property-no-vendor-prefix": true,
    "selector-no-vendor-prefix": true,
    "value-no-vendor-prefix": true,
    // Specificity
    "no-descending-specificity": null,
    "max-nesting-depth": 3,
    "selector-max-compound-selectors": 3,
    "selector-max-specificity": "1,2,1",
    // Miscellanea
    "color-named": "never",
    "declaration-no-important": true,
    "declaration-property-unit-allowed-list": {
      "font-size": ["rem"],
      "/^animation/": ["s"],
    },
    // 'order/properties-alphabetical-order': true,
    "selector-max-type": 1,
    "selector-type-no-unknown": true,
    // Notation
    "font-weight-notation": "numeric",
    // URLs
    "function-url-no-scheme-relative": true,
    "liberty/use-logical-spec": "always",
    "selector-class-pattern": null,
    "alpha-value-notation": null,
    "color-function-notation": null,
    "value-keyword-case": null,
  },
};

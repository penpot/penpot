/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

export const Typography = {
  display: "display",
  titleLarge: "title-large",
  titleMedium: "title-medium",
  titleSmall: "title-small",
  headlineLarge: "headline-large",
  headlineMedium: "headline-medium",
  headlineSmall: "headline-small",
  bodyLarge: "body-large",
  bodyMedium: "body-medium",
  bodySmall: "body-small",
  codeFont: "code-font",
} as const;

export type TypographyId = (typeof Typography)[keyof typeof Typography];

export const typographyIds: TypographyId[] = Object.values(Typography);

export const typographySet: ReadonlySet<string> = new Set(typographyIds);

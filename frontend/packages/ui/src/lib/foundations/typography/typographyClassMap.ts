/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import styles from "./typography.module.scss";

/** Maps a TypographyId value to its corresponding CSS-module class name. */
export const typographyClassMap: Readonly<Record<string, string>> = {
  display: styles["display-typography"],
  "title-large": styles["title-large-typography"],
  "title-medium": styles["title-medium-typography"],
  "title-small": styles["title-small-typography"],
  "headline-large": styles["headline-large-typography"],
  "headline-medium": styles["headline-medium-typography"],
  "headline-small": styles["headline-small-typography"],
  "body-large": styles["body-large-typography"],
  "body-medium": styles["body-medium-typography"],
  "body-small": styles["body-small-typography"],
  "code-font": styles["code-font-typography"],
};

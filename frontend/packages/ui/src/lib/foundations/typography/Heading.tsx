/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";

import type { TypographyId } from "./typography";
import { typographyClassMap } from "./typographyClassMap";

type HeadingLevel = 1 | 2 | 3 | 4 | 5 | 6;
type HeadingTag = "h1" | "h2" | "h3" | "h4" | "h5" | "h6";

export interface HeadingProps extends Omit<
  ComponentPropsWithRef<"h1">,
  "level"
> {
  /** The heading level (1–6). Defaults to `1`. */
  level?: HeadingLevel;
  /** The typography style to apply. */
  typography: TypographyId;
}

function HeadingInner(props: HeadingProps) {
  const { level = 1, typography, className, children, ...rest } = props;
  const Tag: HeadingTag = `h${level}`;
  const resolvedClass = clsx(typographyClassMap[typography], className);

  return (
    <Tag className={resolvedClass} {...rest}>
      {children}
    </Tag>
  );
}

export const Heading = memo(HeadingInner);

/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { type ComponentPropsWithRef, type ElementType, memo } from "react";
import clsx from "clsx";

import type { TypographyId } from "./typography";
import { typographyClassMap } from "./typographyClassMap";

type TextOwnProps<T extends ElementType = "p"> = {
  /** The HTML element or component to render. Defaults to `"p"`. */
  as?: T;
  /** The typography style to apply. */
  typography: TypographyId;
};

export type TextProps<T extends ElementType = "p"> = TextOwnProps<T> &
  Omit<ComponentPropsWithRef<T>, keyof TextOwnProps<T>>;

function TextInner<T extends ElementType = "p">(props: TextProps<T>) {
  const { as, typography, className, children, ...rest } = props;
  const Tag = as ?? "p";
  const resolvedClass = clsx(typographyClassMap[typography], className);

  return (
    <Tag className={resolvedClass} {...rest}>
      {children}
    </Tag>
  );
}

export const Text = memo(TextInner) as typeof TextInner;

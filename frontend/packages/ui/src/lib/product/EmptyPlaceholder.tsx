// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import { RawSvg, type RawSvgId } from "../foundations/assets/RawSvg";
import { Text } from "../foundations/typography/Text";
import styles from "./EmptyPlaceholder.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type EmptyPlaceholderType = 1 | 2;

export interface EmptyPlaceholderProps extends ComponentPropsWithRef<"div"> {
  /** Title text shown in the placeholder. */
  title: string;
  /** Optional subtitle shown below the title. */
  subtitle?: string;
  /** Illustration variant to use. Defaults to 1. */
  type?: EmptyPlaceholderType;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function EmptyPlaceholderInner({
  title,
  subtitle,
  type = 1,
  className,
  children,
  ...rest
}: EmptyPlaceholderProps) {
  const leftId: RawSvgId = `empty-placeholder-${type}-left`;
  const rightId: RawSvgId = `empty-placeholder-${type}-right`;

  return (
    <div
      className={clsx(styles["empty-placeholder"], className)}
      data-testid="empty-placeholder"
      {...rest}
    >
      <RawSvg id={leftId} className={styles["svg-decor"]} />
      <div className={styles["text-wrapper"]}>
        <Text
          as="span"
          typography="title-medium"
          className={styles["placeholder-title"]}
        >
          {title}
        </Text>
        {subtitle && (
          <Text as="span" typography="body-large">
            {subtitle}
          </Text>
        )}
        {children}
      </div>
      <RawSvg id={rightId} className={styles["svg-decor"]} />
    </div>
  );
}

export const EmptyPlaceholder = memo(EmptyPlaceholderInner);

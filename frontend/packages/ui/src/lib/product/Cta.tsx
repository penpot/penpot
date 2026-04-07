/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";

import { Text } from "../foundations/typography/Text";
import styles from "./Cta.module.scss";

export interface CtaProps extends ComponentPropsWithRef<"div"> {
  /** The title text displayed at the top of the CTA block. */
  title: string;
}

function CtaInner({ title, className, children, ...rest }: CtaProps) {
  return (
    <div className={clsx(styles.cta, className)} data-testid="cta" {...rest}>
      <div className={styles["cta-title"]}>
        <Text
          as="span"
          typography="headline-small"
          className={styles["placeholder-title"]}
        >
          {title}
        </Text>
      </div>
      <div className={styles["cta-message"]}>{children}</div>
    </div>
  );
}

export const Cta = memo(CtaInner);

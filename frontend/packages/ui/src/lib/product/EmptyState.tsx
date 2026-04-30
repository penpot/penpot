// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import { Icon } from "../foundations/assets/Icon";
import type { IconId } from "../foundations/assets/Icon";
import styles from "./EmptyState.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface EmptyStateProps extends ComponentPropsWithRef<"div"> {
  /** Icon to display. Required. */
  icon: IconId;
  /** Descriptive text shown below the icon. Required. */
  text: string;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function EmptyStateInner({ icon, text, className, ...rest }: EmptyStateProps) {
  return (
    <div className={clsx(styles.group, className)} {...rest}>
      <div className={styles["icon-wrapper"]}>
        <Icon iconId={icon} size="l" className={styles.icon} />
      </div>
      <div className={styles.text}>{text}</div>
    </div>
  );
}

export const EmptyState = memo(EmptyStateInner);

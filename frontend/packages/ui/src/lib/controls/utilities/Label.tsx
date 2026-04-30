// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import styles from "./Label.module.scss";

export interface LabelProps extends ComponentPropsWithRef<"label"> {
  /** The id of the form control this label is associated with */
  htmlFor: string;
  /** When true, renders an "(Optional)" suffix */
  isOptional?: boolean;
}

function LabelInner({
  htmlFor,
  isOptional = false,
  className,
  children,
  ...rest
}: LabelProps) {
  return (
    <label
      htmlFor={htmlFor}
      className={clsx(styles.label, className)}
      {...rest}
    >
      {children != null && (
        <span className={styles["label-text"]}>{children}</span>
      )}
      {isOptional && (
        <span className={styles["label-optional"]}>(Optional)</span>
      )}
    </label>
  );
}

export const Label = memo(LabelInner);

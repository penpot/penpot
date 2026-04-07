// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import { Icon } from "../foundations/assets/Icon";
import styles from "./Checkbox.module.scss";

export interface CheckboxProps extends Omit<
  ComponentPropsWithRef<"input">,
  "type"
> {
  /** Text label rendered next to the checkbox */
  label?: string;
}

function CheckboxInner({
  id,
  label,
  checked,
  disabled,
  className,
  onChange,
  ...rest
}: CheckboxProps) {
  return (
    <div className={clsx(styles.checkbox, className)}>
      <label htmlFor={id} className={styles["checkbox-label"]}>
        <div
          className={clsx(styles["checkbox-box"], {
            [styles.checked]: checked,
            [styles.disabled]: disabled,
          })}
        >
          {checked && <Icon iconId="tick" size="s" />}
        </div>
        <div className={styles["checkbox-text"]}>{label}</div>
        <input
          type="checkbox"
          id={id}
          checked={checked}
          disabled={disabled}
          onChange={onChange}
          className={styles["checkbox-input"]}
          {...rest}
        />
      </label>
    </div>
  );
}

export const Checkbox = memo(CheckboxInner);

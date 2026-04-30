// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import {
  type ComponentPropsWithRef,
  type KeyboardEvent,
  memo,
  useCallback,
  useId,
  useState,
} from "react";
import clsx from "clsx";
import styles from "./Switch.module.scss";

export interface SwitchProps extends Omit<
  ComponentPropsWithRef<"div">,
  "onChange" | "defaultChecked"
> {
  /** Text label rendered next to the track */
  label?: string;
  /** Accessible label used when there is no visible label */
  "aria-label"?: string;
  /** Initial checked state (uncontrolled). null = neutral/indeterminate. */
  defaultChecked?: boolean | null;
  /** Called with the new boolean state after each toggle */
  onChange?: (checked: boolean) => void;
  /** When true the switch cannot be interacted with */
  disabled?: boolean;
}

function SwitchInner(
  {
    label,
    "aria-label": ariaLabel,
    defaultChecked = null,
    onChange,
    disabled = false,
    className,
    ...rest
  }: SwitchProps,
  ref: React.Ref<HTMLDivElement>,
) {
  const [checked, setChecked] = useState<boolean | null>(
    defaultChecked ?? null,
  );
  const trackId = useId();

  const handleToggle = useCallback(() => {
    if (disabled) return;
    const next = !checked;
    setChecked(next);
    onChange?.(next);
  }, [checked, disabled, onChange]);

  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      if (event.key === " " || event.key === "Enter") {
        event.preventDefault();
        handleToggle();
      }
    },
    [handleToggle],
  );

  const hasLabel = label != null && label.trim().length > 0;

  const rootClass = clsx(
    styles.switch,
    {
      [styles.off]: checked === false,
      [styles.neutral]: checked === null,
      [styles.on]: checked === true,
    },
    className,
  );

  return (
    <div
      ref={ref}
      role="switch"
      aria-label={hasLabel ? undefined : ariaLabel}
      aria-checked={checked ?? false}
      tabIndex={disabled ? -1 : 0}
      className={rootClass}
      onClick={handleToggle}
      onKeyDown={handleKeyDown}
      aria-disabled={disabled || undefined}
      data-disabled={disabled || undefined}
      {...rest}
    >
      <div id={trackId} className={styles["switch-track"]}>
        <div className={styles["switch-thumb"]} />
      </div>
      {hasLabel && (
        <label htmlFor={trackId} className={styles["switch-label"]}>
          {label}
        </label>
      )}
    </div>
  );
}

export const Switch = memo(
  SwitchInner as (
    props: SwitchProps & { ref?: React.Ref<HTMLDivElement> },
  ) => React.ReactElement | null,
);

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo, useCallback } from "react";
import clsx from "clsx";
import { Button } from "../buttons/Button";
import type { ButtonVariant } from "../buttons/Button";
import { IconButton } from "../buttons/IconButton";
import type { IconButtonVariant } from "../buttons/IconButton";
import type { IconId } from "../foundations/assets/Icon";
import styles from "./RadioButtons.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * Variant applied to all buttons in the group.
 * When options have icons, the full IconButtonVariant set is available.
 * For text-only options, "action" falls back to "secondary".
 */
export type RadioButtonVariant = IconButtonVariant;

export interface RadioButtonOption {
  /** Unique id for this option (used as `id` on the hidden input). */
  id: string;
  /** Display label. */
  label: string;
  /** The value this option represents. */
  value: string;
  /** Optional icon id (from the icon sprite). When set, renders an IconButton. */
  icon?: IconId;
  /** Whether this individual option is disabled. */
  disabled?: boolean;
}

export interface RadioButtonsProps extends Omit<
  ComponentPropsWithRef<"div">,
  "onChange"
> {
  /** List of options. */
  options: RadioButtonOption[];
  /** The currently selected value. */
  selected?: string;
  /** `name` attribute shared across all hidden inputs. */
  name?: string;
  /** Button variant applied to all options. */
  variant?: RadioButtonVariant;
  /** When true, each option expands to fill available space. */
  extended?: boolean;
  /** When true, clicking the active option deselects it (acts like checkbox). */
  allowEmpty?: boolean;
  /** Disables the whole group. */
  disabled?: boolean;
  /** Called when the selection changes. Receives the new value (or `null` when deselected). */
  onChange?: (
    value: string | null,
    event: React.ChangeEvent<HTMLInputElement>,
  ) => void;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function RadioButtonsInner({
  options,
  selected,
  name,
  variant = "secondary",
  extended = false,
  allowEmpty = false,
  disabled: wrapperDisabled = false,
  onChange,
  className,
  ...rest
}: RadioButtonsProps) {
  const inputType = allowEmpty ? "checkbox" : "radio";

  // "action" is only valid for IconButton. For text buttons fall back to "secondary".
  const buttonVariant: ButtonVariant =
    variant === "action" ? "secondary" : variant;

  const handleChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const input = event.currentTarget;
      const value = input.value;
      const newValue = allowEmpty && value === selected ? null : value;
      onChange?.(newValue, event);
      input.blur();
    },
    [selected, allowEmpty, onChange],
  );

  return (
    <div
      className={clsx(
        styles.wrapper,
        { [styles.disabled]: wrapperDisabled, [styles.extended]: extended },
        className,
      )}
      {...rest}
    >
      {options.map(({ id, value, label, icon, disabled: optionDisabled }) => {
        const isChecked = selected === value;
        const isDisabled = wrapperDisabled || (optionDisabled ?? false);

        return (
          <label
            key={id}
            htmlFor={id}
            data-label="true"
            data-testid={id}
            className={clsx(styles.label, { [styles.extended]: extended })}
          >
            {icon != null ? (
              <IconButton
                variant={variant}
                aria-pressed={isChecked}
                aria-label={label}
                icon={icon}
                disabled={isDisabled}
              />
            ) : (
              <Button
                variant={buttonVariant}
                aria-pressed={isChecked}
                className={clsx(styles.button, {
                  [styles.extended]: extended,
                })}
                disabled={isDisabled}
              >
                {label}
              </Button>
            )}
            <input
              id={id}
              className={styles.input}
              onChange={handleChange}
              type={inputType}
              name={name}
              disabled={isDisabled}
              value={value}
              checked={isChecked}
              readOnly={onChange == null}
            />
          </label>
        );
      })}
    </div>
  );
}

export const RadioButtons = memo(RadioButtonsInner);

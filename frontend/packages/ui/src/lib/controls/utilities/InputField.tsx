// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import {
  type ComponentPropsWithRef,
  type ReactNode,
  forwardRef,
  memo,
} from "react";
import clsx from "clsx";
import { type IconId, Icon } from "../../foundations/assets/Icon";
import styles from "./InputField.module.scss";

export type InputFieldVariant = "seamless" | "dense" | "comfortable";
export type InputFieldHintType = "hint" | "error" | "warning";

export interface InputFieldProps extends ComponentPropsWithRef<"input"> {
  /** Icon displayed at the leading edge of the field */
  icon?: IconId;
  /** Whether the field has an associated hint/error message */
  hasHint?: boolean;
  /** The type of hint (affects outline colour) */
  hintType?: InputFieldHintType;
  /** Visual density variant */
  variant?: InputFieldVariant;
  /** Content rendered before the input (leading slot) */
  slotStart?: ReactNode;
  /** Content rendered after the input (trailing slot) */
  slotEnd?: ReactNode;
  /** Ref forwarded to the wrapper <div> */
  inputWrapperRef?: React.Ref<HTMLDivElement>;
}

const MAX_INPUT_LENGTH = 500;

function InputFieldInner(
  {
    id,
    icon,
    hasHint = false,
    hintType,
    variant = "dense",
    slotStart,
    slotEnd,
    className,
    inputWrapperRef,
    maxLength,
    "aria-label": ariaLabel,
    "aria-describedby": ariaDescribedby,
    ...rest
  }: InputFieldProps,
  ref: React.Ref<HTMLInputElement>,
) {
  const wrapperClass = clsx(
    styles["input-wrapper"],
    {
      [styles["has-hint"]]: hasHint,
      [styles["hint-type-hint"]]: hintType === "hint",
      [styles["hint-type-warning"]]: hintType === "warning",
      [styles["hint-type-error"]]: hintType === "error",
      [styles["variant-seamless"]]: variant === "seamless",
      [styles["variant-dense"]]: variant === "dense",
      [styles["variant-comfortable"]]: variant === "comfortable",
    },
    className,
  );

  const inputClass = clsx(styles.input, {
    [styles["input-with-icon"]]: icon != null,
  });

  return (
    <div className={wrapperClass} ref={inputWrapperRef}>
      {slotStart}
      {icon != null && <Icon iconId={icon} className={styles.icon} size="s" />}
      <input
        ref={ref}
        id={id}
        className={inputClass}
        maxLength={maxLength ?? MAX_INPUT_LENGTH}
        aria-invalid={hasHint && hintType === "error" ? "true" : undefined}
        aria-describedby={hasHint && id ? `${id}-hint` : ariaDescribedby}
        aria-label={ariaLabel}
        {...rest}
      />
      {slotEnd}
    </div>
  );
}

export const InputField = memo(forwardRef(InputFieldInner));

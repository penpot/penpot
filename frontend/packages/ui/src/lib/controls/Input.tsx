// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { forwardRef, memo, useId } from "react";
import clsx from "clsx";
import { HintMessage } from "./utilities/HintMessage";
import type { HintMessageType } from "./utilities/HintMessage";
import { InputField } from "./utilities/InputField";
import type {
  InputFieldProps,
  InputFieldVariant,
} from "./utilities/InputField";
import { Label } from "./utilities/Label";
import styles from "./Input.module.scss";

export type { InputFieldVariant as InputVariant };
export type { HintMessageType as InputHintType };

export interface InputProps extends InputFieldProps {
  /** Visible label text */
  label?: string;
  /** When true, renders "(Optional)" suffix in the label */
  isOptional?: boolean;
  /** Visual density variant */
  variant?: InputFieldVariant;
  /** Hint / error / warning message displayed below the field */
  hintMessage?: string;
  /** Whether the hint is pre-formatted (preserves whitespace) */
  hintFormatted?: boolean;
  /** Type of hint message */
  hintType?: HintMessageType;
}

function InputInner(
  {
    id: idProp,
    label,
    isOptional = false,
    variant = "dense",
    hintMessage,
    hintFormatted = false,
    hintType,
    className,
    ...rest
  }: InputProps,
  ref: React.Ref<HTMLInputElement>,
) {
  const generatedId = useId();
  const id = idProp ?? generatedId;

  const hasLabel = label != null && label.trim().length > 0;
  const hasHint = hintMessage != null && hintMessage.trim().length > 0;

  const hintClass =
    hintType !== "error" && hintFormatted
      ? styles["hint-formatted"]
      : undefined;

  return (
    <div
      className={clsx(
        styles["input-wrapper"],
        {
          [styles["variant-dense"]]: variant === "dense",
          [styles["variant-comfortable"]]: variant === "comfortable",
          [styles["has-hint"]]: hasHint,
        },
        className,
      )}
    >
      {hasLabel && (
        <Label htmlFor={id} isOptional={isOptional}>
          {label}
        </Label>
      )}
      <InputField
        ref={ref}
        id={id}
        hasHint={hasHint}
        hintType={hintType}
        variant={variant}
        {...rest}
      />
      {hasHint && (
        <HintMessage
          id={id}
          message={hintMessage}
          type={hintType}
          className={hintClass}
        />
      )}
    </div>
  );
}

export const Input = memo(forwardRef(InputInner));

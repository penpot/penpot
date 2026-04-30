// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import { Icon, type IconId } from "../foundations/assets/Icon";
import styles from "./Button.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type ButtonVariant = "primary" | "secondary" | "ghost" | "destructive";

interface ButtonBaseProps {
  /** Visual variant. Defaults to "primary". */
  variant?: ButtonVariant;
  /** Optional icon ID rendered before the label. */
  icon?: IconId;
  /** Callback that receives the underlying DOM element ref. */
  onRef?: (node: HTMLElement | null) => void;
  className?: string;
  children?: React.ReactNode;
}

// Conditional props: when `to` is provided the component renders as <a>;
// otherwise as <button>.
type ButtonAsButton = ButtonBaseProps &
  Omit<ComponentPropsWithRef<"button">, keyof ButtonBaseProps> & {
    /** When set, renders as an anchor element pointing to this URL. */
    to?: undefined;
  };

type ButtonAsAnchor = ButtonBaseProps &
  Omit<ComponentPropsWithRef<"a">, keyof ButtonBaseProps> & {
    /** When set, renders as an anchor element pointing to this URL. */
    to: string;
  };

export type ButtonProps = ButtonAsButton | ButtonAsAnchor;

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function getComputedClass(
  variant: ButtonVariant,
  isLink: boolean,
  className?: string,
) {
  return clsx(
    styles.button,
    {
      [styles["button-link"]]: isLink,
      [styles["button-primary"]]: variant === "primary",
      [styles["button-secondary"]]: variant === "secondary",
      [styles["button-ghost"]]: variant === "ghost",
      [styles["button-destructive"]]: variant === "destructive",
    },
    className,
  );
}

function ButtonContent({
  icon,
  children,
}: {
  icon?: IconId;
  children?: React.ReactNode;
}) {
  return (
    <>
      {icon && <Icon iconId={icon} size="m" />}
      <span className={styles["label-wrapper"]}>{children}</span>
    </>
  );
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function ButtonInner(props: ButtonProps) {
  const { variant = "primary", icon, onRef, className, children } = props;

  if (props.to !== undefined) {
    // Anchor branch
    const { to, variant: _v, icon: _i, onRef: _r, ...rest } = props;
    return (
      <a
        href={to}
        className={getComputedClass(variant, true, className)}
        ref={(node) => onRef?.(node)}
        {...rest}
      >
        <ButtonContent icon={icon}>{children}</ButtonContent>
      </a>
    );
  }

  // Button branch
  const { variant: _v, icon: _i, onRef: _r, to: _t, ...rest } = props;
  return (
    <button
      type="button"
      className={getComputedClass(variant, false, className)}
      ref={(node) => onRef?.(node)}
      {...rest}
    >
      <ButtonContent icon={icon}>{children}</ButtonContent>
    </button>
  );
}

export const Button = memo(ButtonInner);

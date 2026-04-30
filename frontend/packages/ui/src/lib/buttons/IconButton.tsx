// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";
import clsx from "clsx";
import { Icon, type IconId } from "../foundations/assets/Icon";
import styles from "./IconButton.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type IconButtonVariant =
  | "primary"
  | "secondary"
  | "ghost"
  | "destructive"
  | "action";

export interface IconButtonProps extends Omit<
  ComponentPropsWithRef<"button">,
  "children"
> {
  /** Icon to display. Required. */
  icon: IconId;
  /** Accessible label shown as tooltip text and used for aria-label. Required. */
  "aria-label": string;
  /** Visual variant. Defaults to "primary". */
  variant?: IconButtonVariant;
  /** Extra class forwarded to the Icon element. */
  iconClass?: string;
  /** Tooltip placement hint (used when Tooltip component is integrated). */
  tooltipPlacement?:
    | "top"
    | "bottom"
    | "left"
    | "right"
    | "top-right"
    | "bottom-right"
    | "bottom-left"
    | "top-left";
  /** Extra class forwarded to the tooltip wrapper (used when Tooltip is integrated). */
  tooltipClass?: string;
  /** Callback that receives the underlying button DOM node. */
  onRef?: (node: HTMLButtonElement | null) => void;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function IconButtonInner({
  icon,
  variant = "primary",
  iconClass,
  tooltipPlacement: _tooltipPlacement,
  tooltipClass: _tooltipClass,
  onRef,
  className,
  ...rest
}: IconButtonProps) {
  return (
    <button
      type="button"
      className={clsx(
        styles["icon-button"],
        {
          [styles["icon-button-primary"]]: variant === "primary",
          [styles["icon-button-secondary"]]: variant === "secondary",
          [styles["icon-button-ghost"]]: variant === "ghost",
          [styles["icon-button-destructive"]]: variant === "destructive",
          [styles["icon-button-action"]]: variant === "action",
        },
        className,
      )}
      ref={(node) => onRef?.(node)}
      {...rest}
    >
      <Icon iconId={icon} aria-hidden className={iconClass} />
    </button>
  );
}

export const IconButton = memo(IconButtonInner);

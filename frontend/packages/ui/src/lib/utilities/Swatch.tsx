// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { memo, useCallback, useId, useRef } from "react";
import clsx from "clsx";
// Global @property registration — must be a non-module import so the
// @property rule lands in the global scope (CSS Modules cannot scope it).
import "./swatch-properties.scss";
import styles from "./Swatch.module.scss";

// ---------------------------------------------------------------------------
// Color types
// ---------------------------------------------------------------------------

export interface SwatchGradientStop {
  color: string;
  opacity: number;
  offset: number;
}

export interface SwatchGradient {
  type: "linear" | "radial";
  stops: SwatchGradientStop[];
}

export interface SwatchBackground {
  /** Hex colour string, e.g. "#7efff5" */
  color?: string;
  /** Opacity in [0, 1]. Defaults to 1. */
  opacity?: number;
  gradient?: SwatchGradient;
  /** Pre-resolved image URI for image-fill swatches */
  imageUri?: string;
  /** When set the swatch renders with a rounded (circle) border-radius */
  refId?: string;
}

// ---------------------------------------------------------------------------
// CSS helpers (mirrors app.util.color)
// ---------------------------------------------------------------------------

function gradientToCss(gradient: SwatchGradient): string {
  const stopsCss = gradient.stops
    .map(({ color, opacity, offset }) => {
      const hex = color.replace("#", "");
      const full = hex.length === 3 ? hex.replace(/./g, (c) => c + c) : hex;
      const r = parseInt(full.slice(0, 2), 16);
      const g = parseInt(full.slice(2, 4), 16);
      const b = parseInt(full.slice(4, 6), 16);
      return `rgba(${r}, ${g}, ${b}, ${opacity}) ${offset * 100}%`;
    })
    .join(", ");

  return gradient.type === "linear"
    ? `linear-gradient(to bottom, ${stopsCss})`
    : `radial-gradient(circle, ${stopsCss})`;
}

function colorToBackground(background: SwatchBackground): string {
  const { color, opacity = 1, gradient } = background;

  if (gradient) {
    return gradientToCss(gradient);
  }
  if (color) {
    const hex = color.replace("#", "");
    const full = hex.length === 3 ? hex.replace(/./g, (c) => c + c) : hex;
    const r = parseInt(full.slice(0, 2), 16);
    const g = parseInt(full.slice(2, 4), 16);
    const b = parseInt(full.slice(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
  }
  return "transparent";
}

function colorToSolidBackground(background: SwatchBackground): string {
  return colorToBackground({ ...background, opacity: 1 });
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export type SwatchSize = "small" | "medium" | "large";

export interface SwatchProps {
  background?: SwatchBackground;
  /** Visual size of the swatch. Defaults to "small". */
  size?: SwatchSize;
  /** Whether the swatch is in active/selected state */
  active?: boolean;
  /** Show an error indicator instead of the colour */
  hasErrors?: boolean;
  /** Additional CSS class names */
  className?: string;
  /** Click handler — when provided the swatch renders as a <button> */
  onClick?: (
    background: SwatchBackground | undefined,
    event: React.MouseEvent,
  ) => void;
  /** Tooltip content — currently unused until Tooltip is migrated */
  tooltipContent?: React.ReactNode;
  /** Whether to show a tooltip on hover. Defaults to true. */
  showTooltip?: boolean;
  /** Accessible label */
  "aria-label"?: string;
}

function SwatchInner({
  background,
  size = "small",
  active = false,
  hasErrors = false,
  className,
  onClick,
  tooltipContent: _tooltipContent,
  showTooltip: _showTooltip,
  "aria-label": ariaLabel,
}: SwatchProps) {
  const isReadOnly = onClick == null;
  const isRounded = background?.refId != null;
  const elementId = useId();
  // Separate refs typed per element — will be used for Tooltip once migrated
  const divRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const handleClick = useCallback(
    (event: React.MouseEvent) => {
      onClick?.(background, event);
    },
    [background, onClick],
  );

  const rootClass = clsx(
    styles.swatch,
    {
      [styles.small]: size === "small",
      [styles.medium]: size === "medium",
      [styles.large]: size === "large",
      [styles.square]: !isRounded,
      [styles.rounded]: isRounded,
      [styles.active]: active,
      [styles.interactive]: !isReadOnly,
    },
    className,
  );

  const hasOpacity =
    background?.color != null &&
    background.opacity != null &&
    background.opacity < 1;

  const gradientType = background?.gradient?.type;
  const imageUri = background?.imageUri;

  let innerContent: React.ReactNode;

  if (gradientType != null && background?.gradient != null) {
    const gradientCss = gradientToCss(background.gradient);
    const checkerboard =
      "repeating-conic-gradient(lightgray 0% 25%, white 0% 50%)";
    innerContent = (
      <div
        className={styles["swatch-gradient"]}
        style={{
          backgroundImage: `${gradientCss}, ${checkerboard}`,
        }}
      />
    );
  } else if (imageUri != null) {
    innerContent = (
      <div
        className={styles["swatch-image"]}
        style={{ backgroundImage: `url(${imageUri})` }}
      />
    );
  } else if (hasErrors) {
    innerContent = <div className={styles["swatch-error"]} />;
  } else {
    const solidColor = background
      ? colorToSolidBackground(background)
      : "transparent";
    const overlayColor = background
      ? colorToBackground(background)
      : "transparent";
    innerContent = (
      <div className={styles["swatch-opacity"]}>
        <div
          className={styles["swatch-solid-side"]}
          style={{ background: solidColor }}
        />
        <div
          className={clsx(styles["swatch-opacity-side"], {
            [styles["swatch-opacity-side-transparency"]]: hasOpacity,
            [styles["swatch-opacity-side-solid-color"]]: !hasOpacity,
          })}
          style={
            { "--solid-color-overlay": overlayColor } as React.CSSProperties
          }
        />
      </div>
    );
  }

  const sharedProps = {
    className: rootClass,
    "aria-labelledby": elementId,
  };

  // TODO: wrap in <Tooltip> once ds/tooltip/tooltip.cljs is migrated
  return isReadOnly ? (
    <div {...sharedProps} ref={divRef} aria-label={ariaLabel} id={elementId}>
      {innerContent}
    </div>
  ) : (
    <button
      {...sharedProps}
      ref={buttonRef}
      type="button"
      aria-label={ariaLabel}
      id={elementId}
      onClick={handleClick}
    >
      {innerContent}
    </button>
  );
}

export const Swatch = memo(SwatchInner);

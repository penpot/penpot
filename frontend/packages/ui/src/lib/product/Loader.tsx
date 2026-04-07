// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import {
  type ComponentPropsWithRef,
  memo,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import clsx from "clsx";
import styles from "./Loader.module.scss";

// ---------------------------------------------------------------------------
// SVG paths (from CLJS source)
// ---------------------------------------------------------------------------

const LOADER_PATH_1 =
  "M128.273 0l-3.9 2.77L0 91.078l128.273 91.076 549.075-.006V.008L128.273 0zm20.852 30l498.223.006V152.15l-498.223.007V30zm-25 9.74v102.678l-49.033-34.813-.578-32.64 49.61-35.225z";

const LOADER_PATH_2 = "M134.482 157.147v25l518.57.008.002-25-518.572-.008z";

// ---------------------------------------------------------------------------
// Tips (i18n keys — in TS we receive already-translated strings or defaults)
// ---------------------------------------------------------------------------

const DEFAULT_TIPS = [
  {
    title: "Did you know?",
    message: "You can use components to reuse elements across your designs.",
  },
  {
    title: "Pro tip",
    message: "Hold Ctrl/Cmd while dragging to disable snapping temporarily.",
  },
  {
    title: "Pro tip",
    message: "Double-click on a group to enter it and select individual items.",
  },
  {
    title: "Did you know?",
    message: "You can inspect code properties directly in the viewer.",
  },
  {
    title: "Pro tip",
    message:
      "Use pages to organise different versions or sections of your project.",
  },
];

// ---------------------------------------------------------------------------
// Loader icon (internal, private)
// ---------------------------------------------------------------------------

interface LoaderIconProps {
  width: number;
  height: number;
  title: string;
}

function LoaderIcon({ width, height, title }: LoaderIconProps) {
  return (
    <svg
      viewBox="0 0 677.34762 182.15429"
      role="status"
      width={width}
      height={height}
      className={styles.loader}
    >
      <title>{title}</title>
      <g>
        <path d={LOADER_PATH_1} />
        <path className={styles["loader-line"]} d={LOADER_PATH_2} />
      </g>
    </svg>
  );
}

// ---------------------------------------------------------------------------
// Loader
// ---------------------------------------------------------------------------

export interface LoaderTip {
  title: string;
  message: string;
}

export interface LoaderProps extends ComponentPropsWithRef<"div"> {
  /** Width of the loader icon in px. Calculated from height if not given. */
  width?: number;
  /** Height of the loader icon in px. Calculated from width if not given. */
  height?: number;
  /** Accessible title for the loader icon. Defaults to "Loading". */
  title?: string;
  /** When true, the loader covers the full parent area. */
  overlay?: boolean;
  /**
   * When true, the loader enters "file loading" mode — full-screen centred
   * layout with rotating tips shown every 4 seconds.
   */
  fileLoading?: boolean;
  /** Custom tips to cycle through in fileLoading mode. Falls back to built-in tips. */
  tips?: LoaderTip[];
}

function LoaderInner({
  width: widthProp,
  height: heightProp,
  title = "Loading",
  overlay = false,
  fileLoading = false,
  tips: tipsProp,
  className,
  children,
  ...rest
}: LoaderProps) {
  // Mirror CLJS width/height mutual calculation
  const width =
    widthProp ??
    (heightProp != null ? Math.ceil(heightProp * (100 / 27)) : 100);
  const height =
    heightProp ?? (widthProp != null ? Math.ceil(widthProp * (27 / 100)) : 27);

  const tips = useMemo(() => tipsProp ?? DEFAULT_TIPS, [tipsProp]);

  const [tip, setTip] = useState<LoaderTip | null>(null);
  const tipsRef = useRef(tips);

  useEffect(() => {
    tipsRef.current = tips;
  }, [tips]);

  useEffect(() => {
    if (!fileLoading) return;
    // First tip after 1 s, then rotate every 4 s
    const initialTimer = setTimeout(() => {
      setTip(
        tipsRef.current[Math.floor(Math.random() * tipsRef.current.length)],
      );
    }, 1000);
    const interval = setInterval(() => {
      setTip(
        tipsRef.current[Math.floor(Math.random() * tipsRef.current.length)],
      );
    }, 4000);
    return () => {
      clearTimeout(initialTimer);
      clearInterval(interval);
    };
  }, [fileLoading]);

  const rootClass = clsx(
    styles.wrapper,
    {
      [styles["wrapper-overlay"]]: overlay,
      [styles["file-loading"]]: fileLoading,
    },
    className,
  );

  return (
    <div className={rootClass} {...rest}>
      <div className={styles["loader-content"]}>
        <LoaderIcon width={width} height={height} title={title} />
        {fileLoading && tip != null && (
          <div className={styles["tips-container"]}>
            <div className={styles["tip-title"]}>{tip.title}</div>
            <div className={styles["tip-message"]}>{tip.message}</div>
          </div>
        )}
      </div>
      {children}
    </div>
  );
}

export const Loader = memo(LoaderInner);

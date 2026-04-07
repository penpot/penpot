// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { memo } from "react";
import clsx from "clsx";
import { Icon, type IconId } from "../../foundations/assets/Icon";
import { IconButton } from "../../buttons/IconButton";
import styles from "./NotificationPill.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type NotificationPillLevel =
  | "default"
  | "info"
  | "warning"
  | "error"
  | "success";

export type NotificationPillType = "toast" | "context";

export type NotificationPillAppearance = "neutral" | "ghost";

export interface NotificationPillProps {
  /** Severity level – controls colours. */
  level: NotificationPillLevel;
  /** Where this pill is displayed. */
  type: NotificationPillType;
  /** Visual appearance variant. */
  appearance?: NotificationPillAppearance;
  /**
   * When `true`, `children` is treated as an HTML string and injected via
   * `dangerouslySetInnerHTML`. Use only with trusted content.
   */
  isHtml?: boolean;
  /** Optional detail section HTML string. */
  detail?: string;
  /** Controls whether the detail section is expanded. */
  showDetail?: boolean;
  /** Called when the user toggles the detail section. */
  onToggleDetail?: () => void;
  /** Main content. Either a React node or (when `isHtml` is true) an HTML string. */
  children?: React.ReactNode | string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function iconByLevel(level: NotificationPillLevel): IconId {
  switch (level) {
    case "info":
    case "default":
      return "info";
    case "warning":
      return "msg-neutral";
    case "error":
      return "delete-text";
    case "success":
      return "status-tick";
  }
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function NotificationPillInner({
  level,
  type,
  appearance,
  isHtml = false,
  detail,
  showDetail,
  onToggleDetail,
  children,
}: NotificationPillProps) {
  const rootClass = clsx(styles["notification-pill"], {
    [styles["appearance-neutral"]]: appearance === "neutral",
    [styles["appearance-ghost"]]: appearance === "ghost",
    [styles["with-detail"]]: Boolean(detail),
    [styles["type-toast"]]: type === "toast",
    [styles["type-context"]]: type === "context",
    [styles["level-default"]]: level === "default",
    [styles["level-warning"]]: level === "warning",
    [styles["level-error"]]: level === "error",
    [styles["level-success"]]: level === "success",
    [styles["level-info"]]: level === "info",
  });

  const iconId = iconByLevel(level);

  return (
    <div className={rootClass}>
      <div className={styles["error-message"]}>
        <Icon iconId={iconId} className={styles.icon} />
        {isHtml ? (
          <div
            className={styles["context-text"]}
            dangerouslySetInnerHTML={{ __html: children as string }}
          />
        ) : (
          children
        )}
      </div>

      {detail && (
        <div className={styles["error-detail"]}>
          <div className={styles["error-detail-title"]}>
            <IconButton
              icon={showDetail ? "arrow-down" : "arrow"}
              aria-label="Detail"
              iconClass={styles["expand-icon"]}
              variant="action"
              onClick={onToggleDetail}
            />
            <div onClick={onToggleDetail}>Detail</div>
          </div>
          {showDetail && (
            <div
              className={styles["error-detail-content"]}
              dangerouslySetInnerHTML={{ __html: detail }}
            />
          )}
        </div>
      )}
    </div>
  );
}

export const NotificationPill = memo(NotificationPillInner);

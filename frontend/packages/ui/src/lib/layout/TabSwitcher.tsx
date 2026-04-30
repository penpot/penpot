// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import {
  type ComponentPropsWithRef,
  type ReactNode,
  memo,
  useCallback,
  useRef,
} from "react";
import clsx from "clsx";
import { Icon } from "../foundations/assets/Icon";
import type { IconId } from "../foundations/assets/Icon";
import styles from "./TabSwitcher.module.scss";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface TabItem {
  /** Unique tab identifier. Also used as the `id` and `aria-labelledby` value. */
  id: string;
  /** Visible label text. Either `label` or `aria-label` must be provided. */
  label?: string;
  /** Accessible label (for icon-only tabs). */
  "aria-label"?: string;
  /** Icon id. When provided, an icon is shown alongside or instead of the label. */
  icon?: IconId;
}

export interface TabSwitcherProps extends ComponentPropsWithRef<"div"> {
  /** Ordered list of tab definitions. Must have at least one item. */
  tabs: TabItem[];
  /** Id of the currently selected tab. */
  selected: string;
  /** Called when the user selects a different tab. */
  onTabChange: (id: string) => void;
  /** Element rendered alongside the tab nav bar. */
  actionButton?: ReactNode;
  /** Position of the action button relative to the tab list. */
  actionButtonPosition?: "start" | "end";
  /** When true, the tab panel scrolls vertically if its content overflows. */
  scrollablePanel?: boolean;
}

// ---------------------------------------------------------------------------
// Private: Tab button
// ---------------------------------------------------------------------------

interface TabProps {
  id: string;
  label?: string;
  "aria-label"?: string;
  icon?: IconId;
  selected: boolean;
  onClick: (event: React.MouseEvent<HTMLButtonElement>) => void;
  tabRef: (node: HTMLButtonElement | null) => void;
}

function Tab({
  id,
  label,
  "aria-label": ariaLabel,
  icon,
  selected,
  onClick,
  tabRef,
}: TabProps) {
  return (
    <li>
      <button
        id={id}
        ref={tabRef}
        role="tab"
        aria-selected={selected}
        title={label ?? ariaLabel}
        tabIndex={selected ? undefined : -1}
        data-id={id}
        className={clsx(styles.tab, { [styles.selected]: selected })}
        onClick={onClick}
      >
        {icon != null && (
          <Icon
            iconId={icon}
            aria-hidden={label != null ? true : undefined}
            aria-label={label == null ? ariaLabel : undefined}
          />
        )}
        {label != null && (
          <span
            className={clsx(styles["tab-text"], {
              [styles["tab-text-and-icon"]]: icon != null,
            })}
          >
            {label}
          </span>
        )}
      </button>
    </li>
  );
}

// ---------------------------------------------------------------------------
// Private: Tab nav
// ---------------------------------------------------------------------------

interface TabNavProps {
  tabs: TabItem[];
  selected: string;
  actionButton?: ReactNode;
  actionButtonPosition?: "start" | "end";
  onTabClick: (event: React.MouseEvent<HTMLButtonElement>) => void;
  onKeyDown: (event: React.KeyboardEvent<HTMLUListElement>) => void;
  registerRef: (id: string, node: HTMLButtonElement | null) => void;
}

const TabNav = memo(function TabNav({
  tabs,
  selected,
  actionButton,
  actionButtonPosition,
  onTabClick,
  onKeyDown,
  registerRef,
}: TabNavProps) {
  return (
    <nav
      className={clsx(styles["tab-nav"], {
        [styles["tab-nav-start"]]: actionButtonPosition === "start",
        [styles["tab-nav-end"]]: actionButtonPosition === "end",
      })}
    >
      {actionButtonPosition === "start" && actionButton}

      <ul
        role="tablist"
        aria-orientation="horizontal"
        className={styles["tab-list"]}
        onKeyDown={onKeyDown}
      >
        {tabs.map((tab) => (
          <Tab
            key={tab.id}
            id={tab.id}
            label={tab.label}
            aria-label={tab["aria-label"]}
            icon={tab.icon}
            selected={tab.id === selected}
            onClick={onTabClick}
            tabRef={(node) => registerRef(tab.id, node)}
          />
        ))}
      </ul>

      {actionButtonPosition === "end" && actionButton}
    </nav>
  );
});

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

function TabSwitcherInner({
  tabs,
  selected,
  onTabChange,
  actionButton,
  actionButtonPosition,
  scrollablePanel = false,
  className,
  children,
  ...rest
}: TabSwitcherProps) {
  // Map of tab id → button DOM node, for programmatic focus
  const nodesRef = useRef<Record<string, HTMLButtonElement>>({});

  const registerRef = useCallback(
    (id: string, node: HTMLButtonElement | null) => {
      if (node != null) {
        nodesRef.current[id] = node;
      } else {
        delete nodesRef.current[id];
      }
    },
    [],
  );

  const handleTabClick = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      const id = event.currentTarget.dataset["id"];
      if (id != null) onTabChange(id);
    },
    [onTabChange],
  );

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLUListElement>) => {
      const len = tabs.length;
      const currentIndex = tabs.findIndex((t) => t.id === selected);
      let nextId: string | undefined;

      if (event.key === "Home") {
        nextId = tabs[0]?.id;
      } else if (event.key === "ArrowLeft") {
        nextId = tabs[(currentIndex - 1 + len) % len]?.id;
      } else if (event.key === "ArrowRight") {
        nextId = tabs[(currentIndex + 1) % len]?.id;
      }

      if (nextId != null) {
        onTabChange(nextId);
        nodesRef.current[nextId]?.focus();
      }
    },
    [tabs, selected, onTabChange],
  );

  return (
    <div className={clsx(styles.tabs, className)} {...rest}>
      <div className={styles["padding-wrapper"]}>
        <TabNav
          tabs={tabs}
          selected={selected}
          actionButton={actionButton}
          actionButtonPosition={actionButtonPosition}
          onTabClick={handleTabClick}
          onKeyDown={handleKeyDown}
          registerRef={registerRef}
        />
      </div>

      <section
        className={clsx(styles["tab-panel"], {
          [styles["scrollable-panel"]]: scrollablePanel,
        })}
        tabIndex={0}
        role="tabpanel"
        aria-labelledby={selected}
      >
        {children}
      </section>
    </div>
  );
}

export const TabSwitcher = memo(TabSwitcherInner);

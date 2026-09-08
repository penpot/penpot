import {
  Menu as RACMenu,
  MenuItem as RACMenuItem,
  Popover,
  Separator,
  SubmenuTrigger,
} from "react-aria-components";
import type { Key } from "@react-types/shared";
import {
  createContext,
  Fragment,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type MouseEvent as ReactMouseEvent,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import styles from "./Menu.module.scss";

type Placement =
  | "top"
  | "top start"
  | "top end"
  | "bottom"
  | "bottom start"
  | "bottom end"
  | "left"
  | "left top"
  | "left bottom"
  | "right"
  | "right top"
  | "right bottom";

// SubMenu needs a way to close the whole tree (not just its own level) when
// one of its items is selected. MenuTrigger normally provides this via a
// shared RootMenuTriggerStateContext, but Menu/ContextMenu don't use
// MenuTrigger (see below), so that context is never established — this
// fills the same role explicitly.
//
// closing both the root and the submenu popovers at once (rather than just
// the submenu, which is the only thing react-aria itself does on select)
// has to skip their closing CSS animation: react-aria detects animation end
// via each popover's own `getAnimations()`, and closing both simultaneously
// leaves their animations permanently stuck at "running" — neither ever
// settles, so neither popover ever actually unmounts. shouldSkipAnimation
// sidesteps that by closing instantly instead, shared here so the root's
// own Popover and every nested SubMenu's Popover skip it together.
interface MenuCloseController {
  closeAll: () => void;
  shouldSkipAnimation: boolean;
}
const MenuCloseContext = createContext<MenuCloseController | null>(null);

// Lets a "drilldown" SubMenu (see below) replace the menu's own content with
// its items instead of opening a nested flyout popover, for trees too deep
// or too wide for a chain of flyouts (e.g. move-to-project, which nests
// team -> project). Menu/ContextMenu each own one navigation stack and
// provide this to their entire content tree, so a drilldown SubMenu nested
// inside another drilldown SubMenu still drills into the same stack.
interface MenuNavigationController {
  drillIn: (label: ReactNode, content: ReactNode) => void;
}
const MenuNavigationContext =
  createContext<MenuNavigationController | null>(null);

interface NavigationLevel {
  // Distinct per push, so switching levels always fully unmounts the
  // previous level's items and mounts the new ones, rather than updating
  // them in place — react-stately's Collection requires each item's id to
  // stay stable across an update, but the back item's label and every item
  // underneath it genuinely change identity between levels, so this forces
  // a remount instead (React.Fragment key) rather than an update.
  key: string;
  label: ReactNode;
  content: ReactNode;
}

// Renders the back item + separator for whatever level of the navigation
// stack is current, and provides drillIn to the rest of `children`. Shared
// between Menu and ContextMenu, which each keep their own stack (a
// drilldown inside one popover has no bearing on the other).
function useMenuNavigation(children: ReactNode, isOpen: boolean | undefined) {
  const [stack, setStack] = useState<NavigationLevel[]>([]);
  const nextLevelKey = useRef(0);

  useEffect(() => {
    if (!isOpen) setStack([]);
  }, [isOpen]);

  const drillIn = useCallback((label: ReactNode, content: ReactNode) => {
    nextLevelKey.current += 1;
    const key = `level-${nextLevelKey.current}`;
    setStack((prev) => [...prev, { key, label, content }]);
  }, []);

  const drillBack = useCallback(() => {
    setStack((prev) => prev.slice(0, -1));
  }, []);

  const current = stack[stack.length - 1];

  const content = (
    <MenuNavigationContext.Provider value={{ drillIn }}>
      <Fragment key={current ? current.key : "root"}>
        {current && (
          <>
            <MenuItem
              id="__menu-back"
              className={styles.backItem}
              textValue={typeof current.label === "string" ? current.label : undefined}
              shouldCloseOnSelect={false}
              onAction={drillBack}
            >
              <svg
                className={styles.subMenuChevron}
                viewBox="0 0 16 16"
                aria-hidden="true"
              >
                <path
                  d="M10 4l-4 4 4 4"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              <span className={styles.backLabel}>{current.label}</span>
            </MenuItem>
            <MenuSeparator />
          </>
        )}
        {current ? current.content : children}
      </Fragment>
    </MenuNavigationContext.Provider>
  );

  return content;
}

interface MenuProps {
  isOpen?: boolean;
  onOpenChange?: (isOpen: boolean) => void;
  trigger?: ReactNode;
  children: ReactNode;
  placement?: Placement;
  className?: string;
  onAction?: (key: Key) => void;
}

// MenuTrigger normally locates the trigger's DOM node by requiring its
// child to be "pressable" (call usePress() itself, as react-aria-components'
// own Button does). Penpot's DS buttons are plain rumext components that
// don't do that, so MenuTrigger silently gets a null triggerRef and the
// Popover falls back to positioning at (0, 0). As with ContextMenu below,
// this drives the trigger ref explicitly instead of relying on that
// detection.
export function Menu({
  isOpen,
  onOpenChange,
  trigger,
  children,
  placement = "bottom start",
  className,
  onAction,
}: MenuProps) {
  const triggerRef = useRef<HTMLDivElement>(null);
  const triggerId = useId();
  const [shouldSkipAnimation, setShouldSkipAnimation] = useState(false);
  const navigationContent = useMenuNavigation(children, isOpen);

  useEffect(() => {
    if (isOpen) setShouldSkipAnimation(false);
  }, [isOpen]);

  const closeController: MenuCloseController = {
    closeAll: () => {
      setShouldSkipAnimation(true);
      onOpenChange?.(false);
    },
    shouldSkipAnimation,
  };

  return (
    <MenuCloseContext.Provider value={closeController}>
      <div className={styles.menuTrigger} ref={triggerRef} id={triggerId}>
        {trigger}
      </div>
      <Popover
        triggerRef={triggerRef}
        isOpen={isOpen}
        onOpenChange={onOpenChange}
        placement={placement}
        offset={4}
        className={styles.popover}
        shouldSkipAnimation={shouldSkipAnimation}
      >
        <RACMenu
          aria-labelledby={triggerId}
          className={`${styles.menu} ${className ?? ""}`}
          onAction={onAction}
          onClose={() => onOpenChange?.(false)}
          autoFocus="first"
        >
          {navigationContent}
        </RACMenu>
      </Popover>
    </MenuCloseContext.Provider>
  );
}

interface MenuItemProps {
  id?: Key;
  children: ReactNode;
  isDisabled?: boolean;
  onAction?: () => void;
  className?: string;
  textValue?: string;
  // False for an item that navigates (a drilldown SubMenu's own trigger row,
  // the back item) instead of performing an action the menu should close
  // after. Defaults to true, react-aria-components' own default.
  shouldCloseOnSelect?: boolean;
}

export function MenuItem({
  id,
  children,
  isDisabled,
  onAction,
  className,
  textValue,
  shouldCloseOnSelect,
}: MenuItemProps) {
  return (
    <RACMenuItem
      id={id}
      isDisabled={isDisabled}
      onAction={onAction}
      textValue={textValue}
      shouldCloseOnSelect={shouldCloseOnSelect}
      className={`${styles.menuItem} ${className ?? ""}`}
    >
      {children}
    </RACMenuItem>
  );
}

function SubMenuTriggerContent({ trigger }: { trigger: ReactNode }) {
  return (
    <>
      <span className={styles.subMenuLabel}>{trigger}</span>
      <svg
        className={styles.subMenuChevron}
        viewBox="0 0 16 16"
        aria-hidden="true"
      >
        <path
          d="M6 4l4 4-4 4"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </>
  );
}

interface SubMenuProps {
  id?: Key;
  trigger: ReactNode;
  children: ReactNode;
  isDisabled?: boolean;
  textValue?: string;
  className?: string;
  onAction?: (key: Key) => void;
  // "flyout" (default) opens a nested popover next to this item, like a
  // desktop context menu. "drilldown" replaces the parent menu's own
  // content with this submenu's items and adds a back item, for trees too
  // deep/wide for a chain of flyouts (e.g. move-to-project's team ->
  // project nesting). onAction is ignored in drilldown mode: its items sit
  // in the same RACMenu as everything else, so the root Menu/ContextMenu's
  // own onAction already sees them selected.
  variant?: "flyout" | "drilldown";
}

// The submenu's own trigger is always a MenuItem, which — unlike the
// arbitrary trigger passed to Menu/ContextMenu above — is a real
// react-aria-components element that forwards its ref properly. So
// SubmenuTrigger's built-in ref/positioning detection (the thing that
// doesn't work for Penpot's own DS buttons) works fine here, and this can
// use the plain react-aria-components composition.
export function SubMenu({
  id,
  trigger,
  children,
  isDisabled,
  textValue,
  className,
  onAction,
  variant = "flyout",
}: SubMenuProps) {
  const closeController = useContext(MenuCloseContext);
  const navigation = useContext(MenuNavigationContext);

  if (variant === "drilldown") {
    return (
      <MenuItem
        id={id}
        isDisabled={isDisabled}
        textValue={textValue}
        className={styles.subMenuItem}
        shouldCloseOnSelect={false}
        onAction={() => navigation?.drillIn(trigger, children)}
      >
        <SubMenuTriggerContent trigger={trigger} />
      </MenuItem>
    );
  }

  return (
    <SubmenuTrigger>
      <MenuItem
        id={id}
        isDisabled={isDisabled}
        textValue={textValue}
        className={styles.subMenuItem}
      >
        <SubMenuTriggerContent trigger={trigger} />
      </MenuItem>
      <Popover
        className={styles.popover}
        offset={4}
        crossOffset={-4}
        shouldSkipAnimation={closeController?.shouldSkipAnimation}
      >
        <RACMenu
          className={`${styles.menu} ${className ?? ""}`}
          onAction={(key) => {
            onAction?.(key);
            closeController?.closeAll();
          }}
          autoFocus="first"
        >
          {children}
        </RACMenu>
      </Popover>
    </SubmenuTrigger>
  );
}

interface MenuSeparatorProps {
  className?: string;
}

export function MenuSeparator({ className }: MenuSeparatorProps) {
  return <Separator className={`${styles.separator} ${className ?? ""}`} />;
}

interface ContextMenuProps {
  trigger: ReactNode;
  children: ReactNode;
  "aria-label": string;
  placement?: Placement;
  className?: string;
  isDisabled?: boolean;
  onAction?: (key: Key) => void;
}

// MenuTrigger's built-in press/context-menu detection only works when its
// child calls usePress() itself (e.g. react-aria-components' own Button).
// Penpot's own DS buttons aren't react-aria components, so instead of
// relying on that, this drives everything explicitly: a plain onContextMenu
// handler opens a standalone Popover anchored to an invisible element moved
// to the click position.
export function ContextMenu({
  trigger,
  children,
  "aria-label": ariaLabel,
  placement = "bottom start",
  className,
  isDisabled,
  onAction,
}: ContextMenuProps) {
  const anchorRef = useRef<HTMLDivElement>(null);
  const [isOpen, setIsOpen] = useState(false);
  const [shouldSkipAnimation, setShouldSkipAnimation] = useState(false);
  const navigationContent = useMenuNavigation(children, isOpen);

  useEffect(() => {
    if (isOpen) setShouldSkipAnimation(false);
  }, [isOpen]);

  const handleContextMenu = useCallback(
    (e: ReactMouseEvent<HTMLDivElement>) => {
      if (isDisabled) return;
      e.preventDefault();
      const anchor = anchorRef.current;
      if (anchor) {
        anchor.style.left = `${e.clientX}px`;
        anchor.style.top = `${e.clientY}px`;
      }
      setIsOpen(true);
    },
    [isDisabled],
  );

  const closeController: MenuCloseController = {
    closeAll: () => {
      setShouldSkipAnimation(true);
      setIsOpen(false);
    },
    shouldSkipAnimation,
  };

  return (
    <MenuCloseContext.Provider value={closeController}>
      <div
        className={styles.contextMenuTrigger}
        onContextMenu={handleContextMenu}
      >
        {trigger}
      </div>
      {createPortal(
        // Popover itself portals to document.body, so its anchor must too —
        // otherwise an ancestor with a CSS transform (a Storybook decorator,
        // or any app-level one) can make position: fixed here resolve
        // against that ancestor instead of the real viewport, while
        // clientX/clientY (used to place it) always stay viewport-relative.
        <div ref={anchorRef} className={styles.contextMenuAnchor} />,
        document.body,
      )}
      <Popover
        triggerRef={anchorRef}
        isOpen={isOpen}
        onOpenChange={setIsOpen}
        placement={placement}
        offset={0}
        className={styles.popover}
        shouldSkipAnimation={shouldSkipAnimation}
      >
        <RACMenu
          aria-label={ariaLabel}
          className={`${styles.menu} ${className ?? ""}`}
          onAction={onAction}
          onClose={() => setIsOpen(false)}
          autoFocus="first"
        >
          {navigationContent}
        </RACMenu>
      </Popover>
    </MenuCloseContext.Provider>
  );
}

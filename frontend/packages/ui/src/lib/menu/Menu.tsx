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
  | "right";

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
          {children}
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
}

export function MenuItem({
  id,
  children,
  isDisabled,
  onAction,
  className,
  textValue,
}: MenuItemProps) {
  return (
    <RACMenuItem
      id={id}
      isDisabled={isDisabled}
      onAction={onAction}
      textValue={textValue}
      className={`${styles.menuItem} ${className ?? ""}`}
    >
      {children}
    </RACMenuItem>
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
}: SubMenuProps) {
  const closeController = useContext(MenuCloseContext);

  return (
    <SubmenuTrigger>
      <MenuItem
        id={id}
        isDisabled={isDisabled}
        textValue={textValue}
        className={styles.subMenuItem}
      >
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
          {children}
        </RACMenu>
      </Popover>
    </MenuCloseContext.Provider>
  );
}

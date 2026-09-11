// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";
import {
  userEvent,
  fireEvent,
  within,
  screen,
  waitFor,
  expect,
} from "storybook/test";

const { Menu, MenuItem, MenuSeparator, SubMenu, Button } = Components;

const MenuWrapper = ({ children, ...props }) => {
  const [open, setOpen] = React.useState(props.isOpen ?? false);

  React.useEffect(() => {
    setOpen(props.isOpen ?? false);
  }, [props.isOpen]);

  return (
    <Menu
      {...props}
      isOpen={open}
      onOpenChange={setOpen}
      trigger={
        // Toggles rather than always opening, the way a real trigger does
        // (the dashboard's own is a swap! on its open state) — an open menu
        // that closes on the trigger's own pointerdown would reopen here.
        <Button variant="secondary" onClick={() => setOpen((open) => !open)}>
          Open menu
        </Button>
      }
    >
      {children}
    </Menu>
  );
};

export default {
  title: "Layout/Menu",
  component: MenuWrapper,
  args: {
    placement: "bottom start",
    onAction: (key) => console.log("action", key),
    children: (
      <>
        <MenuItem id="rename">Rename</MenuItem>
        <MenuItem id="duplicate">Duplicate</MenuItem>
        <MenuSeparator />
        <MenuItem id="delete">Delete</MenuItem>
      </>
    ),
  },
  argTypes: {
    placement: {
      control: "select",
      options: [
        "top",
        "top start",
        "top end",
        "bottom",
        "bottom start",
        "bottom end",
        "left",
        "left top",
        "left bottom",
        "right",
        "right top",
        "right bottom",
      ],
    },
    maxWidth: {
      control: { type: "number" },
    },
    isDense: {
      control: { type: "boolean" },
    },
  },
  parameters: {
    controls: { exclude: ["isOpen", "onOpenChange", "trigger", "children"] },
  },
  render: ({ ...args }) => <MenuWrapper {...args} />,
};

export const Default = {};

export const WithDisabledItem = {
  args: {
    children: (
      <>
        <MenuItem id="rename">Rename</MenuItem>
        <MenuItem id="duplicate" isDisabled>
          Duplicate
        </MenuItem>
        <MenuSeparator />
        <MenuItem id="delete">Delete</MenuItem>
      </>
    ),
  },
};

const subMenuActionCalls = [];

const subMenuChildren = (
  <>
    <MenuItem id="rename">Rename</MenuItem>
    <SubMenu trigger="Share" onAction={(key) => subMenuActionCalls.push(key)}>
      <MenuItem id="share-link">Copy link</MenuItem>
      <MenuItem id="share-email">Send by email</MenuItem>
    </SubMenu>
    <MenuSeparator />
    <MenuItem id="delete">Delete</MenuItem>
  </>
);

const drilldownChildren = (
  <>
    <MenuItem id="rename">Rename</MenuItem>
    <MenuItem id="duplicate">Duplicate</MenuItem>
    <MenuSeparator />
    <SubMenu trigger="Move to" variant="drilldown">
      <MenuItem id="project-a">Project A</MenuItem>
      <MenuItem id="project-b">Project B</MenuItem>
      <SubMenu trigger="Other team" variant="drilldown">
        <SubMenu trigger="Team 1" variant="drilldown">
          <MenuItem id="team-1-project-a">Project A</MenuItem>
          <MenuItem id="team-1-project-b">Project B</MenuItem>
        </SubMenu>
        <SubMenu trigger="Team 2" variant="drilldown">
          <MenuItem id="team-2-project-a">Project A</MenuItem>
        </SubMenu>
      </SubMenu>
    </SubMenu>
    <MenuSeparator />
    <MenuItem id="delete">Delete</MenuItem>
  </>
);

export const WithSubMenu = {
  args: { children: subMenuChildren },
};

export const WithDrilldownSubMenu = {
  args: { children: drilldownChildren },
};

export const Dense = {
  args: { isDense: true },
};

export const WithMaxWidth = {
  args: {
    maxWidth: 160,
    children: (
      <>
        <MenuItem id="rename">Rename this file completely</MenuItem>
        <MenuItem id="duplicate">Duplicate</MenuItem>
        <MenuSeparator />
        <MenuItem id="delete">Delete</MenuItem>
      </>
    ),
  },
};

export const Placement = {
  args: {
    placement: "right",
  },
  decorators: [
    // Absolutely-positioned + transform centering, rather than flex
    // align-items, because the trigger's own align-self: start (needed so
    // it doesn't get stretched by a real flex/grid ancestor elsewhere)
    // would otherwise override a flex parent's centering here too.
    (Story) => (
      <div style={{ position: "relative", minHeight: "60vh" }}>
        <div
          style={{
            position: "absolute",
            top: "50%",
            left: "50%",
            transform: "translate(-50%, -50%)",
          }}
        >
          <Story />
        </div>
      </div>
    ),
  ],
};

// The popover portals out of the story root, so the menu itself is only
// reachable through screen (document-wide); the trigger stays in the canvas.
const getTrigger = (canvasElement) =>
  within(canvasElement).getByRole("button", { name: /open menu/i });

const expectMenuClosed = () =>
  waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());

export const TestTriggerTogglesMenuClosed = {
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);

    await step("Clicking the trigger opens the menu", async () => {
      await userEvent.click(trigger);
      await screen.findByRole("menu");
    });

    // The trigger sits outside the popover, so a naive outside-click dismiss
    // closes on its pointerdown and lets the click reopen it.
    await step("Clicking the trigger again closes the menu", async () => {
      await userEvent.click(trigger);
      await expectMenuClosed();
    });
  },
};

export const TestFlyoutSubMenuIsNotOutside = {
  args: { children: subMenuChildren },
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);
    subMenuActionCalls.length = 0;

    await step("Hovering the submenu trigger opens the flyout", async () => {
      await userEvent.click(trigger);
      await userEvent.hover(
        await screen.findByRole("menuitem", { name: "Share" }),
      );
      await screen.findByRole("menuitem", { name: "Copy link" });
    });

    // react-aria portals a SubmenuTrigger's popover into the root popover's
    // container, making the flyout a sibling of the root popover rather than
    // a descendant. Testing containment against the root popover alone
    // therefore counts a press anywhere in the flyout as an outside click and
    // dismisses the whole menu.
    await step("A press inside the flyout does not dismiss", async () => {
      const [, flyout] = screen.getAllByRole("menu");
      fireEvent.pointerDown(flyout);

      await waitFor(() =>
        expect(
          screen.getByRole("menuitem", { name: "Copy link" }),
        ).toBeInTheDocument(),
      );
    });

    await step("Selecting a flyout item fires its action", async () => {
      await userEvent.click(
        screen.getByRole("menuitem", { name: "Copy link" }),
      );
      await waitFor(() => expect(subMenuActionCalls).toEqual(["share-link"]));
    });

    await step("Selecting it closes the whole tree", expectMenuClosed);
  },
};

export const TestDrilldownNavigatesAndReturns = {
  args: { children: drilldownChildren },
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);

    await step("Drilling in replaces the menu's own content", async () => {
      await userEvent.click(trigger);
      await userEvent.click(
        await screen.findByRole("menuitem", { name: "Move to" }),
      );

      await screen.findByRole("menuitem", { name: "Project A" });
      expect(
        screen.queryByRole("menuitem", { name: "Rename" }),
      ).not.toBeInTheDocument();
    });

    await step("The back item returns to the level entered from", async () => {
      await userEvent.click(screen.getByRole("menuitem", { name: /move to/i }));

      await screen.findByRole("menuitem", { name: "Rename" });
      expect(
        screen.queryByRole("menuitem", { name: "Project A" }),
      ).not.toBeInTheDocument();
    });
  },
};

export const TestDrilldownResetsBetweenOpens = {
  args: { children: drilldownChildren },
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);

    await step("Drill into a submenu, then close the menu", async () => {
      await userEvent.click(trigger);
      await userEvent.click(
        await screen.findByRole("menuitem", { name: "Move to" }),
      );
      await screen.findByRole("menuitem", { name: "Project A" });

      await userEvent.keyboard("{Escape}");
      await expectMenuClosed();
    });

    await step("Reopening starts back at the root level", async () => {
      await userEvent.click(trigger);

      await screen.findByRole("menuitem", { name: "Rename" });
      expect(
        screen.queryByRole("menuitem", { name: "Project A" }),
      ).not.toBeInTheDocument();
    });
  },
};

export const TestClosesOnEscapeAndOutsideClick = {
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);

    await step("Escape closes the menu", async () => {
      await userEvent.click(trigger);
      await screen.findByRole("menu");

      await userEvent.keyboard("{Escape}");
      await expectMenuClosed();
    });

    await step("A click outside closes the menu", async () => {
      await userEvent.click(trigger);
      await screen.findByRole("menu");

      await userEvent.click(document.body);
      await expectMenuClosed();
    });
  },
};

export const TestMaxWidthCapsPopoverWidth = {
  args: {
    maxWidth: 160,
    children: (
      <>
        <MenuItem id="rename">Rename this file completely</MenuItem>
        <MenuSeparator />
        <MenuItem id="delete">Delete</MenuItem>
      </>
    ),
  },
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);

    await step("The popover never grows past maxWidth", async () => {
      await userEvent.click(trigger);
      const menu = await screen.findByRole("menu");

      await waitFor(() =>
        expect(menu.getBoundingClientRect().width).toBeLessThanOrEqual(160),
      );
    });
  },
};

export const TestDrilldownNeverShrinksBelowRoot = {
  args: {
    // Deliberately wider and taller at the root than the level drilled into,
    // so a regression (sizing the popover off whichever level is current,
    // rather than pinning it to the root) would show up as a shrink.
    children: (
      <>
        <MenuItem id="rename">Rename this file completely</MenuItem>
        <MenuItem id="duplicate">Duplicate</MenuItem>
        <MenuItem id="restore">Restore from trash</MenuItem>
        <MenuSeparator />
        <SubMenu trigger="Move to" variant="drilldown">
          <MenuItem id="project-a">A</MenuItem>
        </SubMenu>
      </>
    ),
  },
  play: async ({ canvasElement, step }) => {
    const trigger = getTrigger(canvasElement);
    let rootSize;

    await step("Measure the root level's content size", async () => {
      await userEvent.click(trigger);
      const menu = await screen.findByRole("menu");
      // scrollWidth/scrollHeight, not getBoundingClientRect: the popover's
      // own max-block-size is recomputed by react-aria against the trigger's
      // position and can be transiently smaller right after opening, which
      // would make this assert against the wrong (clipped) baseline.
      rootSize = { width: menu.scrollWidth, height: menu.scrollHeight };
    });

    await step("Drilling in sets a min-size pinned to the root", async () => {
      await userEvent.click(screen.getByRole("menuitem", { name: "Move to" }));
      const menu = await screen.findByRole("menu");
      await screen.findByRole("menuitem", { name: "A" });

      // Checked against the min-inline-size/min-block-size this sets, not
      // the rendered box: that box is still subject to the same transient
      // max-block-size react-aria computes for the popover, independent of
      // whether the fix under test applied the right floor underneath it.
      await waitFor(() => {
        expect(parseFloat(menu.style.minInlineSize)).toBeCloseTo(
          rootSize.width,
          0,
        );
        expect(parseFloat(menu.style.minBlockSize)).toBeCloseTo(
          rootSize.height,
          0,
        );
      });
    });
  },
};

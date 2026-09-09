// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";
import {
  fireEvent,
  screen,
  userEvent,
  waitFor,
  within,
  expect,
} from "storybook/test";

const { ContextMenu, MenuItem, MenuSeparator } = Components;

const ContextMenuWrapper = ({ children, ...props }) => {
  return (
    <ContextMenu
      {...props}
      trigger={
        <div
          style={{
            display: "grid",
            placeItems: "center",
            inlineSize: "16rem",
            blockSize: "10rem",
            border: "1px dashed var(--color-background-quaternary)",
            borderRadius: "8px",
            color: "var(--color-foreground-secondary)",
          }}
        >
          Right click here
        </div>
      }
    >
      {children}
    </ContextMenu>
  );
};

export default {
  title: "Layout/Context Menu",
  component: ContextMenuWrapper,
  args: {
    "aria-label": "Item actions",
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
    isDisabled: { control: "boolean" },
  },
  parameters: {
    controls: { exclude: ["trigger", "children"] },
  },
  render: ({ ...args }) => <ContextMenuWrapper {...args} />,
};

export const Default = {};

export const Disabled = {
  args: {
    isDisabled: true,
  },
};

const openChangeCalls = [];

export const TestOnOpenChangeReportsState = {
  args: {
    onOpenChange: (isOpen) => openChangeCalls.push(isOpen),
  },
  play: async ({ canvasElement, step }) => {
    openChangeCalls.length = 0;
    const trigger = within(canvasElement).getByText("Right click here");

    await step("Right-clicking the trigger reports it open", async () => {
      fireEvent.contextMenu(trigger);
      await screen.findByRole("menu");
      await waitFor(() => expect(openChangeCalls).toEqual([true]));
    });

    await step("Escape reports it closed", async () => {
      await userEvent.keyboard("{Escape}");
      await waitFor(() => expect(openChangeCalls).toEqual([true, false]));
    });
  },
};

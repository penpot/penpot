// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

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
        <Button variant="secondary" onClick={() => setOpen(true)}>
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
        "right",
      ],
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

export const WithSubMenu = {
  args: {
    children: (
      <>
        <MenuItem id="rename">Rename</MenuItem>
        <SubMenu
          trigger="Share"
          onAction={(key) => console.log("sub-menu action", key)}
        >
          <MenuItem id="share-link">Copy link</MenuItem>
          <MenuItem id="share-email">Send by email</MenuItem>
        </SubMenu>
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

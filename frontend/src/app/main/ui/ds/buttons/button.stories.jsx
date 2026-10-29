// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";
import { within, expect } from "storybook/test";

const { Button } = Components;
const { icons } = Components.meta;

export default {
  title: "Buttons/Button",
  component: Components.Button,
  argTypes: {
    icon: {
      options: icons,
      control: { type: "select" },
    },
    disabled: { control: "boolean" },
    variant: {
      options: ["primary", "secondary", "ghost", "destructive"],
      control: { type: "select" },
    },
  },
  args: {
    children: "Lorem ipsum",
    disabled: false,
    variant: undefined,
    type: "button",
  },
  parameters: {
    controls: { exclude: ["children"] },
  },
  render: ({ ...args }) => <Button {...args} />,
};

export const Default = {};

export const WithIcon = {
  args: {
    icon: "effects",
  },
};

export const Primary = {
  args: {
    variant: "primary",
  },
};

export const Secondary = {
  args: {
    variant: "secondary",
  },
};

export const Ghost = {
  args: {
    variant: "ghost",
  },
};

export const Destructive = {
  args: {
    variant: "destructive",
  },
};

// With `to`, the button renders an anchor: it keeps the button look but it is
// a real link (href, open in a new tab, announced as a link).
export const AsLink = {
  args: {
    variant: "secondary",
    to: "https://penpot.app",
    children: "Go to Penpot",
  },
};

export const TestAsLinkRendersAnchor = {
  args: AsLink.args,
  play: async ({ canvasElement }) => {
    const link = within(canvasElement).getByRole("link", {
      name: "Go to Penpot",
    });
    await expect(link).toHaveAttribute("href", "https://penpot.app");
    // `type` on an anchor is a MIME type hint, not a button type.
    await expect(link).not.toHaveAttribute("type");
  },
};

export const TestButtonDefaultsToTypeButton = {
  play: async ({ canvasElement }) => {
    const button = within(canvasElement).getByRole("button", {
      name: "Lorem ipsum",
    });
    await expect(button).toHaveAttribute("type", "button");
  },
};

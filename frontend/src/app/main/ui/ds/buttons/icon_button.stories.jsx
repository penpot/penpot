// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { IconButton } = Components;
const { icons } = Components.meta;

const iconList = [
  ...Object.entries(icons)
    .map(([_, value]) => value)
    .sort(),
];

export default {
  title: "Buttons/IconButton",
  component: Components.IconButton,
  argTypes: {
    icon: {
      options: iconList,
      control: { type: "select" },
    },
    disabled: { control: "boolean" },
    variant: {
      options: ["primary", "secondary", "ghost", "destructive", "action"],
      control: { type: "select" },
    },
  },
  args: {
    disabled: false,
    variant: undefined,
    "aria-label": "Lorem ipsum",
    icon: "effects",
  },
  render: ({ ...args }) => <IconButton {...args} />,
};

export const Default = {};

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

export const Action = {
  args: {
    variant: "action",
  },
};

export const Destructive = {
  args: {
    variant: "destructive",
  },
};

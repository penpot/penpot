// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

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

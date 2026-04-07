// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { iconIds } from "../foundations/assets/Icon";
import { IconButton } from "./IconButton";

const meta = {
  title: "Buttons/IconButton",
  component: IconButton,
  args: {
    icon: "effects",
    "aria-label": "Effects",
    variant: "primary",
  },
  argTypes: {
    icon: {
      options: iconIds,
      control: { type: "select" },
    },
    variant: {
      options: ["primary", "secondary", "ghost", "destructive", "action"],
      control: { type: "select" },
    },
    disabled: { control: "boolean" },
  },
} satisfies Meta<typeof IconButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Primary: Story = {
  args: { variant: "primary" },
};

export const Secondary: Story = {
  args: { variant: "secondary" },
};

export const Ghost: Story = {
  args: { variant: "ghost" },
};

export const Action: Story = {
  args: { variant: "action" },
};

export const Destructive: Story = {
  args: { variant: "destructive" },
};

export const Disabled: Story = {
  args: { disabled: true },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { iconIds } from "../foundations/assets/Icon";
import { Button } from "./Button";

const meta = {
  title: "Buttons/Button",
  component: Button,
  args: {
    children: "Lorem ipsum",
    variant: "primary",
  },
  argTypes: {
    icon: {
      options: [undefined, ...iconIds],
      control: { type: "select" },
    },
    variant: {
      options: ["primary", "secondary", "ghost", "destructive"],
      control: { type: "select" },
    },
  },
} satisfies Meta<typeof Button>;

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

export const Destructive: Story = {
  args: { variant: "destructive" },
};

export const WithIcon: Story = {
  args: { icon: "effects" },
};

export const AsLink: Story = {
  args: { to: "https://penpot.app" },
};

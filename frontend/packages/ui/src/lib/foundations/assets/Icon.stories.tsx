// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Icon, iconIds } from "./Icon";

const meta = {
  title: "Foundations/Assets/Icon",
  component: Icon,
  args: {
    iconId: "pin",
    size: "m",
  },
  argTypes: {
    iconId: {
      options: iconIds,
      control: { type: "select" },
    },
    size: {
      options: ["s", "m", "l"],
      control: { type: "radio" },
    },
  },
} satisfies Meta<typeof Icon>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Small: Story = {
  args: { size: "s" },
};

export const Large: Story = {
  args: { size: "l" },
};

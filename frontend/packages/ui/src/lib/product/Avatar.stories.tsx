// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Avatar } from "./Avatar";

const profile = {
  fullname: "Ada Lovelace",
  photoUrl: "/images/avatar-blue.jpg",
};

const profileNoPhoto = {
  fullname: "Ada Lovelace",
};

const meta = {
  title: "Product/Avatar",
  component: Avatar,
  args: {
    profile,
    variant: "S",
    selected: false,
  },
  argTypes: {
    variant: {
      options: ["S", "M", "L"],
      control: { type: "select" },
    },
    selected: { control: "boolean" },
  },
} satisfies Meta<typeof Avatar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const NoURL: Story = {
  args: {
    profile: profileNoPhoto,
  },
};

export const Small: Story = {
  args: { variant: "S" },
};

export const Medium: Story = {
  args: { variant: "M" },
};

export const Large: Story = {
  args: { variant: "L" },
};

export const Selected: Story = {
  args: { selected: true },
};

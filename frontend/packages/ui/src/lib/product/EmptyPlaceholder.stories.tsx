// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { EmptyPlaceholder } from "./EmptyPlaceholder";

const meta = {
  title: "Product/EmptyPlaceholder",
  component: EmptyPlaceholder,
  args: {
    title: "Nothing here yet",
    subtitle: "Create something to get started.",
    type: 1,
  },
} satisfies Meta<typeof EmptyPlaceholder>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Type2: Story = {
  args: { type: 2 },
};

export const NoSubtitle: Story = {
  args: { subtitle: undefined },
};

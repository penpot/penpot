// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { HintMessage } from "./HintMessage";

const meta = {
  title: "Controls/Utilities/HintMessage",
  component: HintMessage,
  args: {
    id: "field",
    message: "This is a hint message.",
  },
} satisfies Meta<typeof HintMessage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Warning: Story = {
  args: { type: "warning", message: "This value is unusual." },
};

export const Error: Story = {
  args: { type: "error", message: "This field is required." },
};

export const NoMessage: Story = {
  args: { message: undefined },
};

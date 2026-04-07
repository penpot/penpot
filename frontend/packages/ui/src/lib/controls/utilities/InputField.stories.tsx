// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { InputField } from "./InputField";

const meta = {
  title: "Controls/Utilities/InputField",
  component: InputField,
  args: {
    id: "field",
    placeholder: "Type something…",
  },
} satisfies Meta<typeof InputField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Dense: Story = {
  args: { variant: "dense" },
};

export const Comfortable: Story = {
  args: { variant: "comfortable" },
};

export const Seamless: Story = {
  args: { variant: "seamless" },
};

export const WithIcon: Story = {
  args: { icon: "search" },
};

export const WithError: Story = {
  args: { hasHint: true, hintType: "error" },
};

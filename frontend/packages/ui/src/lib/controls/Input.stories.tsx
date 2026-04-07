// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Input } from "./Input";

const meta = {
  title: "Controls/Input",
  component: Input,
  args: {
    label: "Field label",
    placeholder: "Type something…",
  },
} satisfies Meta<typeof Input>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Optional: Story = {
  args: { isOptional: true },
};

export const WithHint: Story = {
  args: { hintMessage: "This is a hint." },
};

export const WithError: Story = {
  args: { hintMessage: "This field is required.", hintType: "error" },
};

export const WithWarning: Story = {
  args: { hintMessage: "Value is unusual.", hintType: "warning" },
};

export const Comfortable: Story = {
  args: { variant: "comfortable" },
};

export const NoLabel: Story = {
  args: { label: undefined },
};

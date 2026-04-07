// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Switch } from "./Switch";

const meta = {
  title: "Controls/Switch",
  component: Switch,
  args: {
    label: "Enable feature",
  },
} satisfies Meta<typeof Switch>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const On: Story = {
  args: { defaultChecked: true },
};

export const Off: Story = {
  args: { defaultChecked: false },
};

export const Neutral: Story = {
  args: { defaultChecked: null },
};

export const Disabled: Story = {
  args: { disabled: true, defaultChecked: false },
};

export const DisabledOn: Story = {
  args: { disabled: true, defaultChecked: true },
};

export const NoLabel: Story = {
  args: { label: undefined, "aria-label": "Toggle feature" },
};

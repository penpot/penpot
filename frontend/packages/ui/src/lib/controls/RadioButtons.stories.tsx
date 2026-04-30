// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { RadioButtons } from "./RadioButtons";
import type { RadioButtonOption, RadioButtonsProps } from "./RadioButtons";

const options: RadioButtonOption[] = [
  { id: "left", label: "Left", value: "left" },
  { id: "center", label: "Center", value: "center" },
  { id: "right", label: "Right", value: "right" },
];

const optionsDisabled: RadioButtonOption[] = [
  { id: "left", label: "Left", value: "left" },
  { id: "center", label: "Center", value: "center", disabled: true },
  { id: "right", label: "Right", value: "right" },
];

const optionsIcon: RadioButtonOption[] = [
  { id: "left", label: "Left align", value: "left", icon: "text-align-left" },
  {
    id: "center",
    label: "Center align",
    value: "center",
    icon: "text-align-center",
  },
  {
    id: "right",
    label: "Right align",
    value: "right",
    icon: "text-align-right",
  },
];

function StatefulRadioButtons(args: RadioButtonsProps) {
  const [selected, setSelected] = useState(args.selected ?? "left");
  return (
    <RadioButtons
      {...args}
      selected={selected}
      onChange={(value) => {
        if (value != null) setSelected(value);
      }}
    />
  );
}

const meta = {
  title: "Controls/Radio Buttons",
  component: RadioButtons,
  args: {
    name: "alignment",
    selected: "left",
    extended: false,
    allowEmpty: false,
    options,
    disabled: false,
  },
  render: (args) => <StatefulRadioButtons {...args} />,
} satisfies Meta<typeof RadioButtons>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithIcons: Story = {
  args: {
    options: optionsIcon,
  },
};

export const WithOptionDisabled: Story = {
  args: {
    options: optionsDisabled,
  },
};

export const Extended: Story = {
  args: {
    extended: true,
  },
};

export const AllowEmpty: Story = {
  args: {
    allowEmpty: true,
    selected: undefined,
  },
};

export const DisabledGroup: Story = {
  args: {
    disabled: true,
  },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { RadioButtons } = Components;

const options = [
  { id: "left", label: "Left", value: "left" },
  { id: "center", label: "Center", value: "center" },
  { id: "right", label: "Right", value: "right" },
];

const optionsIcon = [
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

export default {
  title: "Controls/Radio Buttons",
  component: RadioButtons,
  argTypes: {
    name: {
      control: { type: "text" },
      description: "Whether the checkbox is checked",
    },
    selected: {
      control: { type: "select" },
      options: ["", "left", "center", "right"],
      description: "Whether the checkbox is checked",
    },
    extended: {
      control: { type: "boolean" },
      description: "Whether the checkbox is checked",
    },
    allowEmpty: {
      control: { type: "boolean" },
      description: "Whether the checkbox is checked",
    },
    disabled: {
      control: { type: "boolean" },
      description: "Whether the checkbox is disabled",
    },
  },
  args: {
    name: "alignment",
    selected: "left",
    extended: false,
    allowEmpty: false,
    options: options,
    disabled: false,
  },
  parameters: {
    controls: {
      exclude: ["options", "on-change"],
    },
  },
  render: ({ ...args }) => <RadioButtons {...args} />,
};

export const Default = {};

export const WithIcons = {
  args: {
    options: optionsIcon,
  },
};

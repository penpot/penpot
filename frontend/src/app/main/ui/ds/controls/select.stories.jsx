// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Select } = Components;

const variants = [
  "default",
  "ghost",
];

const options = [
  { id: "option-code", label: "Code" },
  { id: "option-design", label: "Design" },
  { id: "", label: "(Empty)" },
  { id: "option-menu", label: "Menu" },
];

const optionsWithIcons = [
  { id: "option-code", label: "Code", icon: "fill-content" },
  { id: "option-design", label: "Design", icon: "pentool" },
  { id: "", label: "(Empty)" },
  { id: "option-menu", label: "Menu" },
];

export default {
  title: "Controls/Select",
  component: Select,
  argTypes: {
    disabled: { control: "boolean" },
    emptyToEnd: { control: "boolean" },
    variant: {
      control: { type: "select" },
      options: variants,
    },
  },
  args: {
    disabled: false,
    options: options,
    emptyToEnd: false,
    defaultSelected: "option-code",
    variant: variants[0],
  },
  parameters: {
    controls: {
      exclude: ["options", "defaultSelected"],
    },
    docs: {
      story: {
        height: "200px",
      },
    },
  },
  render: ({ ...args }) => <Select {...args} />,
};

export const Default = {};

export const Ghost = {
  args: {
    variant: "ghost",
  },
};

export const WithIcons = {
  args: {
    options: optionsWithIcons,
  },
};

export const EmptyToEnd = {
  args: {
    emptyToEnd: true,
  },
};

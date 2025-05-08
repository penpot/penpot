// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Input } = Components;
const { icons } = Components.meta;

export default {
  title: "Controls/Input",
  component: Components.Input,
  argTypes: {
    defaultValue: {
      control: { type: "text" },
    },
    label: {
      control: { type: "text" },
    },
    placeholder: {
      control: { type: "text" },
    },
    isOptional: { control: { type: "boolean" } },
    icon: {
      options: icons,
      control: { type: "select" },
    },
    type: {
      options: ["text", "email", "password"],
      control: { type: "select" },
    },
    hintMessage: {
      control: { type: "text" },
    },
    hintType: {
      options: ["hint", "error", "warning"],
      control: { type: "select" },
    },
    variant: {
      options: ["dense", "comfortable", "seamless"],
      control: { type: "select" },
    },
    disabled: {
      control: { type: "boolean" },
    },
  },
  args: {
    id: "input",
    label: "Label",
    isOptional: false,
    defaultValue: "Value",
    placeholder: "Placeholder",
    type: "text",
    icon: "search",
    hintMessage: "This is a hint text to help user.",
    hintType: "hint",
    variant: "dense",
    disabled: false,
  },
  parameters: {
    controls: { exclude: ["id"] },
  },
  render: ({ ...args }) => <Input {...args} />,
};

export const Default = {};

export const Dense = {
  args: {
    variant: "dense",
  },
};

export const Comfortable = {
  args: {
    variant: "comfortable",
  },
};

export const Seamless = {
  args: {
    variant: "seamless",
  },
};

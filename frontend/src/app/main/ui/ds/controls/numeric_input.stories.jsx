// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC
import * as React from "react";
import Components from "@target/components";

const { NumericInput } = Components;
const { icons } = Components.meta;

export default {
  title: "Controls/Numeric Input",
  component: Components.NumericInput,
  argTypes: {
    placeholder: {
      control: { type: "text" },
    },
    disabled: {
      control: { type: "boolean" },
    },
    nillable: {
      control: { type: "boolean" },
    },
    min: {
      control: { type: "number" },
    },
    max: {
      control: { type: "number" },
    },
    step: {
      control: { type: "number" },
    },
    icon: {
      options: icons,
      control: { type: "select" },
    },
  },
  args: {
    placeholder: "--",
    disabled: false,
    nillable: false,
    icon: "search",
  },
  parameters: {
    controls: { exclude: ["id"] },
  },
  render: ({ ...args }) => <NumericInput {...args} />,
};

export const Default = {};

export const WithTokens = {
  args: {
    placeholder: "--",
    disabled: false,
    nillable: false,
    icon: "search",
    tokens: {
      min: 0,
      max: 100,
      step: 1,
    },
    options: [
      { group: true, name: "Dimensions"},
      { id: "1", name: "Token 1", resolvedValue: "10" },
      { id: "2", name: "Token 2", resolvedValue: "20" },
      { id: "3", name: "Token 3", resolvedValue: "30" },
    ],
  },
  render: ({ ...args }) => <NumericInput {...args} />,
};
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
    property: "search",
  },
  parameters: {
    controls: { exclude: ["tokens"] },
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
    min: 0,
    max: 100,
    step: 1,
    tokens: {
      dimensions: [
        {
          id: "ef79ae43-3f3f-8008-8006-988189c5e56e",
          name: "dimension-1",
          type: "dimensions",
          value: "30",
          description: "",
          "modified-at": "2025-08-04T12:02:00.087-00:00",
          resolvedValue: 30,
          unit: null,
        },
        {
          id: "ef79ae43-3f3f-8008-8006-98a5d53d85d0",
          name: "dimension-2",
          type: "dimensions",
          value: "20",
          description: "",
          "modified-at": "2025-08-04T14:40:34.550-00:00",
          resolvedValue: 20,
          unit: null,
        },
        {
          id: "ef79ae43-3f3f-8008-8006-98a5dd078629",
          name: "dimension-3",
          type: "dimensions",
          value: "100",
          description: "",
          "modified-at": "2025-08-04T14:40:42.526-00:00",
          resolvedValue: 100,
          unit: null,
        },
        {
          id: "ef79ae43-3f3f-8008-8006-98a5e75c0299",
          name: "dimension-4",
          type: "dimensions",
          value: "500",
          description: "",
          "modified-at": "2025-08-04T14:40:53.104-00:00",
          resolvedValue: 500,
          unit: null,
        },
      ],
      spacing: [
        {
          id: "ef79ae43-3f3f-8008-8006-98a5f1c64d5a",
          name: "spacing-1",
          type: "spacing",
          value: "32",
          description: "",
          "modified-at": "2025-08-04T14:41:03.769-00:00",
          resolvedValue: 32,
          unit: "px",
        },
      ],
    },
  },
  render: ({ ...args }) => <NumericInput {...args} />,
};

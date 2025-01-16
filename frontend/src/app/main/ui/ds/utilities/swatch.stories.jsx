// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";
import { helpers } from "@target/components";
import { action } from "@storybook/addon-actions";

const { Swatch } = Components;

export default {
  title: "Foundations/Utilities/Swatch",
  component: Swatch,
  argTypes: {
    background: {
      control: "object",
    },
    size: {
      control: "select",
      options: ["small", "medium", "large"],
    },
    active: {
      control: { type: "boolean" },
    },
  },
  args: {
    background: { color: "#7efff5" },
    size: "medium",
    active: false,
  },
  render: ({ ...args }) => <Swatch {...args} />,
};

export const Default = {};

export const WithOpacity = {
  args: {
    background: {
      color: "#2f226c",
      opacity: 0.5,
    },
  },
};

const stops = [
  {
    color: "#151035",
    opacity: 1,
    offset: 0,
  },
  {
    color: "#2f226c",
    opacity: 0.5,
    offset: 1,
  },
];

export const LinearGradient = {
  args: {
    background: {
      gradient: {
        type: helpers.keyword("linear"),
        "start-x": 0,
        "start-y": 0,
        "end-x": 1,
        "end-y": 0,
        width: 1,
        stops,
      },
    },
  },
};

export const RadialGradient = {
  args: {
    background: {
      gradient: {
        type: helpers.keyword("radial"),
        "start-x": 0,
        "start-y": 0,
        "end-x": 1,
        "end-y": 0,
        width: 1,
        stops,
      },
    },
  },
};

export const Rounded = {
  args: {
    background: {
      id: helpers.generateUuid(),
      color: "#2f226c",
      opacity: 0.5,
    },
  },
};

export const Clickable = {
  args: {
    onClick: action("on-click"),
    "aria-label": "Click swatch",
  },
};

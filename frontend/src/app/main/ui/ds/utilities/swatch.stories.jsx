// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";
import { action } from "@storybook/addon-actions";

const { Swatch } = Components;

export default {
  title: "Foundations/Utilities/Swatch",
  component: Swatch,
  argTypes: {
    background: {
      control: { type: "text" },
    },
    format: {
      control: "select",
      options: ["square", "rounded"],
    },
    size: {
      control: "select",
      options: ["small", "medium"],
    },
    active: {
      control: { type: "boolean" },
    },
  },
  args: {
    background: "#663399",
    format: "square",
    size: "medium",
    active: false,
  },
  render: ({ ...args }) => <Swatch {...args} />,
};

export const Default = {};

export const WithOpacity = {
  args: {
    background: "rgba(255, 0, 0, 0.5)",
  },
};

export const LinearGradient = {
  args: {
    background: "linear-gradient(to right, transparent, mistyrose)",
  },
};

export const Image = {
  args: {
    background: "images/form/never-used.png",
    size: "medium",
  },
};

export const Rounded = {
  args: {
    format: "rounded",
  },
};

export const Small = {
  args: {
    size: "small",
  },
};

export const Active = {
  args: {
    active: true,
    background: "#CC00CC",
  },
};

export const Clickable = {
  args: {
    onClick: action("on-click"),
    "aria-label": "Click swatch",
  },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Select } = Components;

export default {
  title: "Controls/Select",
  component: Select,
  argTypes: {
    disabled: { control: "boolean" },
  },
  args: {
    disabled: false,
    options: [
      {
        label: "Code",
        id: "option-code",
      },
      {
        label: "Design",
        id: "option-design",
      },
      {
        label: "Menu",
        id: "option-menu",
      },
    ],
    defaultSelected: "option-code",
  },
  parameters: {
    controls: {
      exclude: ["options", "defaultSelected"],
    },
  },
  render: ({ ...args }) => <Select {...args} />,
};

export const Default = {};

export const WithIcons = {
  args: {
    options: [
      {
        label: "Code",
        id: "option-code",
        icon: "fill-content",
      },
      {
        label: "Design",
        id: "option-design",
        icon: "pentool",
      },
      {
        label: "Menu",
        id: "option-menu",
      },
    ],
  },
};

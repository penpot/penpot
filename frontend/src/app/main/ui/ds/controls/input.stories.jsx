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
    icon: {
      options: icons,
      control: { type: "select" },
    },
    value: {
      control: { type: "text" },
    },
    disabled: { control: "boolean" },
  },
  args: {
    disabled: false,
    value: "Lorem ipsum",
  },
  render: ({ ...args }) => <Input {...args} />,
};

export const Default = {};

export const WithIcon = {
  args: {
    icon: "effects",
  },
};

export const WithPlaceholder = {
  args: {
    icon: "effects",
    value: undefined,
    placeholder: "Mixed",
  },
};

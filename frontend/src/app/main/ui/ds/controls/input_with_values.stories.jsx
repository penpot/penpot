// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { InputWithValues } = Components;

export default {
  title: "Controls/InputWithValues",
  component: Components.InputWithValues,
  argTypes: {
    name: {
      control: { type: "text" },
    },
    values: {
      control: { type: "text" },
    },
  },
  args: {
    name: "Property 1",
    values: "Value1, Value2",
  },
  render: ({ ...args }) => <InputWithValues {...args} />,
};

export const Default = {};

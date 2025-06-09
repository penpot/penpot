// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { InputWithMeta } = Components;

export default {
  title: "Product/InputWithMeta",
  component: Components.InputWithMeta,
  argTypes: {
    value: {
      control: { type: "text" },
    },
    meta: {
      control: { type: "text" },
    },
    maxLength: {
      control: { type: "number" },
    },
  },
  args: {
    value: "Property 1",
    meta: "Value1, Value2",
    maxLength: 10,
  },
  render: ({ ...args }) => <InputWithMeta {...args} />,
};

export const Default = {};

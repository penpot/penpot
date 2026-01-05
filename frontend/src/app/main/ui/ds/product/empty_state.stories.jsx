// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { EmptyState } = Components;
const { icons } = Components.meta;

export default {
  title: "Product/EmptyState",
  component: Components.EmptyState,
  argTypes: {
    icon: {
      options: icons,
      control: { type: "select" },
    },
    text: {
      control: { type: "text" },
    },
  },
  args: {
    icon: "help",
    text: "This is an empty state",
  },
  render: ({ ...args }) => <EmptyState {...args} />,
};

export const Default = {};

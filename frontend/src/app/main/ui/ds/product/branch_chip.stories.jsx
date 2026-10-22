// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { BranchChip } = Components;

export default {
  title: "Product/Branching/BranchChip",
  component: BranchChip,
  argTypes: {
    label: {
      control: { type: "text" },
    },
    isOpen: {
      control: { type: "boolean" },
    },
  },
  args: {
    label: "feature-login",
    isOpen: false,
  },
  render: ({ ...args }) => <BranchChip {...args} />,
};

export const Default = {};

export const Main = {
  args: {
    label: "Main",
  },
};

export const Open = {
  args: {
    isOpen: true,
  },
};

export const LongName = {
  args: {
    label: "redesign/checkout-flow-v2-with-a-very-long-name",
  },
  render: ({ ...args }) => (
    <div style={{ inlineSize: "200px" }}>
      <BranchChip {...args} />
    </div>
  ),
};

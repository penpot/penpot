// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { BranchPill } = Components;

export default {
  title: "Product/Branching/BranchPill",
  component: BranchPill,
  argTypes: {
    label: {
      control: { type: "text" },
    },
    isMain: {
      control: { type: "boolean" },
    },
    isReview: {
      control: { type: "boolean" },
    },
  },
  args: {
    label: "feature-login",
    isMain: false,
    isReview: false,
  },
  render: ({ ...args }) => <BranchPill {...args} />,
};

export const Default = {};

export const Main = {
  args: {
    label: "Main",
    isMain: true,
  },
};

export const Review = {
  args: {
    isReview: true,
  },
};

export const Route = {
  render: () => (
    <div style={{ display: "flex", alignItems: "center", gap: "4px" }}>
      <BranchPill label="feature-login" isReview={true} />
      <span>→</span>
      <BranchPill label="Main" isMain={true} />
    </div>
  ),
};

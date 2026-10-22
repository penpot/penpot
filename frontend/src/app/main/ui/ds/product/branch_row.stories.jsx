// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { BranchRow, BranchStatus } = Components;

export default {
  title: "Product/Branching/BranchRow",
  component: BranchRow,
  argTypes: {
    label: {
      control: { type: "text" },
    },
    isCurrent: {
      control: { type: "boolean" },
    },
  },
  args: {
    label: "onboarding-v2",
    isCurrent: false,
  },
  render: ({ ...args }) => (
    <div style={{ inlineSize: "270px" }}>
      <BranchRow {...args} />
    </div>
  ),
};

export const Default = {};

export const Current = {
  args: {
    label: "feature-login",
    isCurrent: true,
  },
};

export const WithStatus = {
  render: ({ ...args }) => (
    <div style={{ inlineSize: "270px" }}>
      <BranchRow {...args}>
        <BranchStatus ahead={5} review="approved" approvals={2} reviewers={2} />
      </BranchRow>
    </div>
  ),
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { BranchStatus } = Components;

export default {
  title: "Product/Branching/BranchStatus",
  component: BranchStatus,
  argTypes: {
    ahead: {
      control: { type: "number", min: 0 },
    },
    behind: {
      control: { type: "number", min: 0 },
    },
    hasError: {
      control: { type: "boolean" },
    },
    conflicts: {
      control: { type: "number", min: 0 },
    },
    review: {
      control: "select",
      options: [undefined, "pending", "approved"],
    },
    approvals: {
      control: { type: "number", min: 0 },
    },
    reviewers: {
      control: { type: "number", min: 0 },
    },
  },
  args: {
    ahead: 3,
    behind: 1,
    hasError: false,
    conflicts: 0,
  },
  render: ({ ...args }) => <BranchStatus {...args} />,
};

export const Default = {};

export const InReview = {
  args: {
    behind: 0,
    review: "pending",
    approvals: 1,
    reviewers: 2,
  },
};

export const Approved = {
  args: {
    behind: 0,
    review: "approved",
    approvals: 2,
    reviewers: 2,
  },
};

export const WithConflicts = {
  args: {
    behind: 4,
    conflicts: 2,
    review: "pending",
    approvals: 1,
    reviewers: 2,
  },
};

export const ComparisonFailed = {
  args: {
    hasError: true,
  },
};

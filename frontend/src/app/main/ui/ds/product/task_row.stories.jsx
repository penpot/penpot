// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { TaskRow } = Components;

export default {
  title: "Product/Review/TaskRow",
  component: TaskRow,
  argTypes: {
    status: {
      options: ["open", "done", "reopened", "verified", "wont-do"],
      control: { type: "select" },
    },
    isSelected: {
      control: { type: "boolean" },
    },
    isLayerLost: {
      control: { type: "boolean" },
    },
  },
  args: {
    title: "Add 24 px between the card fields and the summary",
    status: "open",
    statusLabel: "Open",
    layer: "Card form",
    isSelected: false,
    isLayerLost: false,
  },
  render: ({ ...args }) => (
    <div style={{ inlineSize: "286px" }}>
      <TaskRow {...args} />
    </div>
  ),
};

export const Default = {};

export const Selected = {
  args: {
    isSelected: true,
  },
};

export const InAReview = {
  args: {
    isSelected: true,
    isReview: true,
    review: "Checkout redesign",
  },
};

export const LostLayer = {
  args: {
    title: "Replace the promo copy (waiting for marketing)",
    status: "reopened",
    statusLabel: "Reopened",
    layer: "Promo banner",
    isLayerLost: true,
  },
};

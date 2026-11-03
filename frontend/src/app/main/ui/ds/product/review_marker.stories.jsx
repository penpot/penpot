// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import * as React from "react";
import Components from "@target/components";

const { ReviewMarker } = Components;

export default {
  title: "Product/Review/ReviewMarker",
  component: ReviewMarker,
  argTypes: {
    type: {
      options: ["comment", "task"],
      control: { type: "select" },
    },
    state: {
      options: ["default", "active", "resolved", "lost"],
      control: { type: "select" },
    },
    label: {
      control: { type: "text" },
    },
  },
  args: {
    type: "comment",
    state: "default",
    label: "1",
    "aria-label": "Comment 1",
  },
  render: ({ ...args }) => <ReviewMarker {...args} />,
};

export const Default = {};

export const AllMarkers = {
  render: () => (
    <div style={{ display: "flex", gap: "8px" }}>
      <ReviewMarker label="1" aria-label="Comment 1" />
      <ReviewMarker label="2" state="active" aria-label="Comment 2" />
      <ReviewMarker state="resolved" aria-label="Comment 3" />
      <ReviewMarker type="task" aria-label="Task" />
      <ReviewMarker type="task" state="active" aria-label="Task" />
      <ReviewMarker type="task" state="lost" aria-label="Task" />
    </div>
  ),
};

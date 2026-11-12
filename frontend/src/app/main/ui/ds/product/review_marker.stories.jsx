// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

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
      options: ["default", "active", "resolved", "lost", "ghost"],
      control: { type: "select" },
    },
    label: {
      control: { type: "text" },
    },
    counter: {
      control: { type: "number" },
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
      <ReviewMarker type="task" counter={3} aria-label="3 tasks" />
      <ReviewMarker counter={2} aria-label="2 comments" />
      <ReviewMarker
        type="task"
        state="ghost"
        counter={3}
        aria-label="3 tasks"
      />
    </div>
  ),
};

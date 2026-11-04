// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { TaskStatus } = Components;

export default {
  title: "Product/Review/TaskStatus",
  component: TaskStatus,
  argTypes: {
    status: {
      options: ["open", "done", "reopened", "verified", "wont-do"],
      control: { type: "select" },
    },
    label: {
      control: { type: "text" },
    },
  },
  args: {
    status: "open",
    label: "Open",
  },
  render: ({ ...args }) => <TaskStatus {...args} />,
};

export const Default = {};

export const AllStatuses = {
  render: () => (
    <div style={{ display: "flex", gap: "8px" }}>
      <TaskStatus status="open" label="Open" />
      <TaskStatus status="done" label="Done" />
      <TaskStatus status="reopened" label="Reopened" />
      <TaskStatus status="verified" label="Verified" />
      <TaskStatus status="wont-do" label="Won't do" />
    </div>
  ),
};

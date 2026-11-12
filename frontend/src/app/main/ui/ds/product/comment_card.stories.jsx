// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { CommentCard } = Components;

export default {
  title: "Product/Review/CommentCard",
  component: CommentCard,
  argTypes: {
    isOpen: { control: { type: "boolean" } },
    isResolved: { control: { type: "boolean" } },
  },
  args: {
    location: "#1 - Board",
    author: "Ana Pérez",
    time: "1 hour ago",
    replies: "No replies",
    children: "The summary should sit closer to the form.",
    isOpen: false,
    isResolved: false,
  },
  render: ({ ...args }) => (
    <div style={{ inlineSize: "286px" }}>
      <CommentCard {...args} />
    </div>
  ),
};

export const Default = {};

export const Open = {
  args: {
    isOpen: true,
  },
};

export const InAReview = {
  args: {
    review: "Checkout redesign",
    isReview: true,
    isOpen: true,
  },
};

export const Resolved = {
  args: {
    isResolved: true,
  },
};

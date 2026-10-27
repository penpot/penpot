// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { ChangeRow, Tag, Checkbox } = Components;
const tones = [
  "neutral",
  "accent",
  "info",
  "warning",
  "error",
  "success",
  "purple",
];

export default {
  title: "Product/Branching/ChangeRow",
  component: ChangeRow,
  argTypes: {
    tone: { options: tones, control: { type: "select" } },
    isSelected: { control: { type: "boolean" } },
    isMuted: { control: { type: "boolean" } },
  },
  args: {
    title: "Plans / row",
    subtitle: "Spaced out: gap 8 → 24",
    icon: "board",
    tone: "info",
    isSelected: false,
    isMuted: false,
  },
  render: ({ ...args }) => (
    <div style={{ width: "290px" }}>
      <ChangeRow {...args} />
    </div>
  ),
};

export const Default = {};

export const WithTag = {
  args: {
    title: "Card / Promo",
    subtitle: "Corner radius - both changed",
    tone: "warning",
    end: (
      <Tag tone="warning" variant="pill">
        Choose
      </Tag>
    ),
  },
};

export const WithCheckbox = {
  args: {
    title: "Promo banner",
    subtitle: "New board - left out",
    tone: "success",
    isMuted: true,
    start: <Checkbox id="row-check" checked={false} />,
  },
};

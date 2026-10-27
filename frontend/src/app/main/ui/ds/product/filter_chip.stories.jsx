// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { FilterChip } = Components;
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
  title: "Product/Branching/FilterChip",
  component: FilterChip,
  argTypes: {
    tone: { options: tones, control: { type: "select" } },
    isSelected: { control: { type: "boolean" } },
  },
  args: { label: "Decisions (2)", tone: "warning", isSelected: false },
  render: ({ ...args }) => <FilterChip {...args} />,
};

export const Default = {};

export const Selected = { args: { isSelected: true } };

export const WithIcon = {
  args: { label: "Copy (1)", icon: "text", tone: "neutral" },
};

export const Bar = {
  render: () => (
    <div style={{ display: "flex", gap: "8px" }}>
      <FilterChip label="All (19)" isSelected={true} />
      <FilterChip label="Decisions (2)" icon="triangle-alert" tone="warning" />
      <FilterChip label="Copy (1)" icon="text" />
      <FilterChip label="Color (1)" icon="swatches" />
      <FilterChip label="Components (1)" icon="component" />
    </div>
  ),
};

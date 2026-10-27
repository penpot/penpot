// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { Tag } = Components;
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
  title: "Product/Branching/Tag",
  component: Tag,
  argTypes: {
    tone: { options: tones, control: { type: "select" } },
    variant: { options: ["label", "pill"], control: { type: "radio" } },
  },
  args: { tone: "neutral", variant: "label", children: "Board" },
  render: ({ ...args }) => <Tag {...args} />,
};

export const Default = {};

export const Pill = {
  args: { variant: "pill", tone: "warning", children: "Choose" },
};

export const WithIcon = {
  args: { icon: "component", children: "Components" },
};

export const Tones = {
  render: () => (
    <div style={{ display: "flex", flexWrap: "wrap", gap: "8px" }}>
      {tones.map((tone) => (
        <Tag key={tone} tone={tone}>
          {tone}
        </Tag>
      ))}
      {tones.map((tone) => (
        <Tag key={"pill-" + tone} tone={tone} variant="pill">
          {tone}
        </Tag>
      ))}
    </div>
  ),
};

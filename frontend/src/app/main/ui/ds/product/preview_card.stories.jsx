// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { PreviewCard } = Components;

const Sample = ({ text }) => (
  <div
    style={{
      padding: "12px",
      borderRadius: "6px",
      background: "#2e3434",
      color: "#fff",
    }}
  >
    {text}
  </div>
);

export default {
  title: "Product/Branching/PreviewCard",
  component: PreviewCard,
  argTypes: {
    side: {
      options: ["base", "main", "branch", "result"],
      control: { type: "radio" },
    },
    isWarning: { control: { type: "boolean" } },
    isLarge: { control: { type: "boolean" } },
  },
  args: { label: "Branch", side: "branch", detail: "You", isWarning: false },
  render: ({ ...args }) => (
    <div style={{ width: "340px" }}>
      <PreviewCard {...args}>
        <Sample text="Summer offer" />
      </PreviewCard>
    </div>
  ),
};

export const Default = {};

export const Sides = {
  render: () => (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(3, 1fr)",
        gap: "12px",
        width: "740px",
      }}
    >
      <PreviewCard label="Main" side="main" detail="2 hours ago">
        <Sample text="12" />
      </PreviewCard>
      <PreviewCard label="Branch" side="branch" detail="You">
        <Sample text="4" />
      </PreviewCard>
      <PreviewCard label="Result" side="result">
        <Sample text="4" />
      </PreviewCard>
    </div>
  ),
};

export const Large = {
  args: { isLarge: true },
};

export const WithCaption = {
  args: {
    label: "Result",
    side: "result",
    detail: "Merged as stored",
    caption: "Card 4 from main lands on card 3.",
    isWarning: true,
  },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { LinkButton } = Components;

export default {
  title: "Buttons/LinkButton",
  component: LinkButton,
  argTypes: {
    tone: {
      options: ["accent", "info", "neutral"],
      control: { type: "select" },
    },
  },
  args: {
    tone: "accent",
    children: "See more",
  },
  render: ({ ...args }) => <LinkButton {...args} />,
};

export const Default = {};

export const AllTones = {
  render: () => (
    <div style={{ display: "flex", gap: "16px" }}>
      <LinkButton>Accent</LinkButton>
      <LinkButton tone="info">Info</LinkButton>
      <LinkButton tone="neutral">Neutral</LinkButton>
    </div>
  ),
};

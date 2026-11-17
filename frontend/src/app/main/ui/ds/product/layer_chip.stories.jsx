// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { LayerChip } = Components;

export default {
  title: "Product/Review/LayerChip",
  component: LayerChip,
  argTypes: {
    isLost: { control: { type: "boolean" } },
    isStruck: { control: { type: "boolean" } },
  },
  args: {
    label: "Card form",
    isLost: false,
    isStruck: false,
  },
  render: ({ ...args }) => <LayerChip {...args} />,
};

export const Default = {};

export const AllChips = {
  render: () => (
    <div style={{ display: "flex", gap: "8px" }}>
      <LayerChip label="Card form" />
      <LayerChip label="Promo banner" isLost />
      <LayerChip label="Promo banner" isLost isStruck />
    </div>
  ),
};

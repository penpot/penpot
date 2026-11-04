// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { ProgressBar } = Components;

export default {
  title: "Product/Review/ProgressBar",
  component: ProgressBar,
  argTypes: {
    value: { control: { type: "number" } },
    total: { control: { type: "number" } },
  },
  args: {
    value: 2,
    total: 7,
  },
  render: ({ ...args }) => (
    <div style={{ inlineSize: "286px" }}>
      <ProgressBar {...args} />
    </div>
  ),
};

export const Default = {};

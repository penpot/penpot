// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { ReviewLabel } = Components;

export default {
  title: "Product/Review/ReviewLabel",
  component: ReviewLabel,
  args: {
    label: "Checkout redesign",
  },
  render: ({ ...args }) => (
    <div style={{ display: "flex", inlineSize: "286px" }}>
      <ReviewLabel {...args} />
    </div>
  ),
};

export const Default = {};

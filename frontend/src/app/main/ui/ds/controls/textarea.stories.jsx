// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { Textarea } = Components;

export default {
  title: "Controls/Textarea",
  component: Textarea,
  args: {
    rows: 3,
    placeholder: "What needs to be done?",
    "aria-label": "Title",
  },
  render: ({ ...args }) => (
    <div style={{ inlineSize: "286px" }}>
      <Textarea {...args} />
    </div>
  ),
};

export const Default = {};

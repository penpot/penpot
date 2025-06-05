// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC
import * as React from "react";
import Components from "@target/components";

const { NumericInput } = Components;

export default {
  title: "Controls/Numeric Input",
  component: Components.NumericInput,
  argTypes: {

  },
  args: {
  },
  parameters: {
    controls: { exclude: ["id"] },
  },
  render: ({ ...args }) => <NumericInput  { ...args }/>,
};

export const Default = {};
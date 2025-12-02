// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Checkbox } = Components;

export default {
  title: "Controls/Checkbox",
  component: Checkbox,
  argTypes: {
    label: {
      control: { type: "text" },
      description: "Label text displayed next to the checkbox",
    },
    checked: {
      control: { type: "boolean" },
      description: "Whether the checkbox is checked",
    },
    disabled: {
      control: { type: "boolean" },
      description: "Whether the checkbox is disabled",
    },
  },
  args: {
    checked: false,
    disabled: false,
  },
  parameters: {
    controls: {
      exclude: ["id", "on-change"],
    },
  },
  render: ({ ...args }) => <Checkbox {...args} />,
};

export const Default = {
  args: {
    label: "Toggle something",
    disabled: false,
  },
  render: ({ ...args }) => <Checkbox {...args} />,
};

export const Checked = {
  args: {
    label: "Toggle something",
    checked: true,
    disabled: false,
  },
  render: ({ ...args }) => <Checkbox {...args} />,
};

export const WithoutLabel = {
  args: {
    disabled: false,
  },
  render: ({ ...args }) => <Checkbox {...args} />,
};

export const WithLongLabel = {
  args: {
    label:
      "This is a very long label that demonstrates how the checkbox component handles text wrapping and layout when the label content is extensive",
    disabled: false,
  },
  render: ({ ...args }) => (
    <div style={{ maxWidth: "300px" }}>
      <Checkbox {...args} />
    </div>
  ),
};

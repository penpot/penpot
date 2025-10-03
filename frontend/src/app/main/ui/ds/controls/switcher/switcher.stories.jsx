// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Switcher } = Components;

export default {
  title: "Controls/Switcher",
  component: Switcher,
  argTypes: {
    defaultChecked: {
      control: { type: "boolean" },
      description: "Default checked state for uncontrolled mode",
    },
    label: {
      control: { type: "text" },
      description: "Label text displayed next to the switcher",
    },
    disabled: {
      control: { type: "boolean" },
      description: "Whether the switcher is disabled",
    },
    size: {
      options: ["sm", "md", "lg"],
      control: { type: "select" },
      description: "Size variant of the switcher",
    }
  },
  args: {
    disabled: false,
    size: "md",
    defaultChecked: false
  },
  parameters: {
    controls: { exclude: ["id", "class", "dataTestid", "on-change"] },
  },
  render: ({ onChange, ...args }) => (
    <Switcher {...args} onChange={onChange} label="Enable notifications"/>
  ),
};
export const Default = {};

export const WithLongLabel = {
  args: {
    label: "This is a very long label that demonstrates how the switcher component handles text wrapping and layout when the label content is extensive",
    defaultChecked: true,
  },
  render: ({ ...args }) => (
    <div style={{ maxWidth: "300px" }}>
      <Switcher {...args}/>
    </div>
  ),
};

export const WithoutVisibleLabel = {
  args: {
    defaultChecked: false,
  },
  render: ({ ...args }) => (
    <Switcher {...args}  aria-label="Enable notification"/>
  ),
};
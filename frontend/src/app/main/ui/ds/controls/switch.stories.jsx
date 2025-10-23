// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Switch } = Components;

export default {
  title: "Controls/Switch",
  component: Switch,
  argTypes: {
    defaultChecked: {
      control: { type: "boolean" },
      description: "Default checked state for uncontrolled mode",
    },
    label: {
      control: { type: "text" },
      description: "Label text displayed next to the switch",
    },
    disabled: {
      control: { type: "boolean" },
      description: "Whether the switch is disabled",
    }
  },
  args: {
    defaultChecked: false,
    disabled: false
  },
  parameters: {
    controls: { exclude: ["id", "class", "aria-label", "on-change"] },
  },
  render: ({ ...args }) => (
    <Switch {...args} />
  ),
};

export const Default = {};

export const WithLabel = {
  args: {
    defaultChecked: false,
    label: "Enable something",
    disabled: false,
  },
  render: ({ ...args }) => (
    <Switch {...args} aria-label="Enable notification"/>
  ),
};

export const WithLongLabel = {
  args: {
    defaultChecked: false,
    label: "This is a very long label that demonstrates how the switch component handles text wrapping and layout when the label content is extensive",
    disabled: false,
  },
  render: ({ ...args }) => (
    <div style={{ maxWidth: "300px" }}>
      <Switch {...args} />
    </div>
  ),
};

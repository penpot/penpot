// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Combobox } = Components;

export default {
  title: "Controls/Combobox",
  component: Combobox,
  argTypes: {
    disabled: { control: "boolean" },
  },
  args: {
    disabled: false,
    options: [
      { id: 'January', label: 'January' },
      { id: 'February', label: 'February' },
      { id: 'March', label: 'March' },
      { id: 'April', label: 'April' },
      { id: 'May', label: 'May' },
      { id: 'June', label: 'June' },
      { id: 'July', label: 'July' },
      { id: 'August', label: 'August' },
      { id: 'September', label: 'September' },
      { id: 'October', label: 'October' },
      { id: 'November', label: 'November' },
      { id: 'December', label: 'December' },
    ],
    defaultSelected: 'February',
  },
  parameters: {
    controls: {
      exclude: ["options", "defaultSelected"],
    },
  },
  render: ({ ...args }) => <Combobox {...args} />,
};

export const Default = {
  parameters: {
    docs: {
      story: {
        height: '450px',
      },
    },
  }
};

export const WithIcons = {
  args: {
    options: [
      { id: 'January', label: 'January', icon: "fill-content" },
      { id: 'February', label: 'February', icon: "pentool" },
      { id: 'March', label: 'March' },
      { id: 'April', label: 'April' },
      { id: 'May', label: 'May' },
      { id: 'June', label: 'June' },
      { id: 'July', label: 'July' },
      { id: 'August', label: 'August' },
      { id: 'September', label: 'September' },
      { id: 'October', label: 'October' },
      { id: 'November', label: 'November' },
      { id: 'December', label: 'December' },
    ],
  },
  parameters: {
    docs: {
      story: {
        height: '450px',
      },
    },
  }
};

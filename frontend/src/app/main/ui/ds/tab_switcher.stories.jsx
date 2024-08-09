// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { TabSwitcher } = Components;

export default {
  title: "Tab switcher",
  component: TabSwitcher,
  args: {
    tabs: [
      { label: "Code", id: "tab-code", content: <p>Lorem Ipsum</p> },
      { label: "Design", id: "tab-design", content: <p>dolor sit amet</p> },
      {
        label: "Menu",
        id: "tab-menu",
        buttonPosition: null,
        content: <p>consectetur adipiscing elit</p>,
      },
    ],
    defaultSelected: "tab-code",
  },
  argTypes: {
    defaultSelected: {
      control: "select",
      options: ["tab-code", "tab-design", "tab-menu"],
    },
    tabs: {
      control: false,
    },
    buttonPosition: { control: "select", options: ["start", "end", null] },
  },
  parameters: {},
  render: ({ ...args }) => <TabSwitcher {...args} />,
};

export const Default = {};

export const WithCollapsableButton = {
  args: {
    buttonPosition: "start",
  },
};

export const WithIcons = {
  args: {
    tabs: [
      {
        "aria-label": "Code",
        id: "tab-code",
        icon: "fill-content",
        content: <p>Lorem Ipsum</p>,
      },
      {
        "aria-label": "Design",
        id: "tab-design",
        icon: "pentool",
        content: <p>dolor sit amet</p>,
      },
      {
        "aria-label": "Menu",
        id: "tab-menu",
        icon: "mask",
        content: <p>consectetur adipiscing elit</p>,
      },
    ],
  },
};

export const WithIconsAndText = {
  args: {
    tabs: [
      {
        label: "Code",
        id: "tab-code",
        icon: "fill-content",
        content: <p>Lorem Ipsum</p>,
      },
      {
        label: "Design",
        id: "tab-design",
        icon: "pentool",
        content: <p>dolor sit amet</p>,
      },
      {
        label: "Menu",
        id: "tab-menu",
        icon: "mask",
        content: <p>consectetur adipiscing elit</p>,
      },
    ],
  },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Toast } = Components;

export default {
  title: "Notifications/Toast",
  component: Toast,
  argTypes: {
    children: {
      control: { type: "text" },
    },
  },
  args: {
    children: "Lorem ipsum",
    onClose: () => {
      alert("Close callback");
    },
  },
  parameters: {
    controls: {
      exclude: ["onClose"],
    },
  },
  render: ({ ...args }) => <Toast {...args} />,
};

export const Default = {};

export const WithLongerText = {
  args: {
    children:
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent lorem ante, bibendum sed ex.",
  },
};

export const Info = {
  args: {
    level: "info",
  },
  parameters: {
    controls: { exclude: ["level", "onClose"] },
  },
};

export const Error = {
  args: {
    level: "error",
  },
  parameters: {
    controls: { exclude: ["level", "onClose"] },
  },
};

export const Warning = {
  args: {
    level: "warning",
  },
  parameters: {
    controls: { exclude: ["level", "onClose"] },
  },
};

export const Success = {
  args: {
    level: "success",
  },
  parameters: {
    controls: { exclude: ["level", "onClose"] },
  },
};

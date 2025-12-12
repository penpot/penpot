// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";
import { action } from "storybook/actions";

const { Toast } = Components;

export default {
  title: "Notifications/Toast",
  component: Toast,
  argTypes: {
    children: {
      control: { type: "text" },
    },
    detail: {
      control: { type: "text" },
    },
    showDetail: {
      control: { type: "boolean" },
    },
  },
  args: {
    children: "Lorem ipsum",
    type: "toast",
    onClose: action("on-close"),
  },
  parameters: {
    controls: {
      exclude: ["onClose", "type"],
    },
  },
  render: ({ ...args }) => <Toast {...args} />,
};

export const Base = {};

export const WithLongerText = {
  args: {
    children:
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent lorem ante, bibendum sed ex.",
  },
};

export const WithDetail = {
  args: {
    detail:
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent lorem ante, bibendum sed ex.",
    showDetail: true,
  },
};

export const WithHTML = {
  args: {
    children:
      "Lorem ipsum dolor sit amet, <marquee>consectetur adipiscing elit.</marquee> Praesent lorem ante, bibendum sed ex.",
    isHtml: true,
  },
  parameters: {
    controls: { exclude: ["isHtml"] },
  },
};

export const Default = {
  args: {
    level: "default",
  },
  parameters: {
    controls: { exclude: ["level", "onClose"] },
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

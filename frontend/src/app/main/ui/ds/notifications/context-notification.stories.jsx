// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { ContextNotification } = Components;

export default {
  title: "Notifications/ContextNotification",
  component: ContextNotification,
  argTypes: {
    children: {
      control: { type: "text" },
    },
    appearance: {
      options: ["neutral", "ghost"],
      control: { type: "select" },
    },
    level: {
      options: ["default", "info", "error", "warning", "success"],
      control: { type: "select" },
    },
  },
  args: {
    children: "Lorem ipsum",
    isHtml: false,
    type: "context",
    appearance: "neutral",
    level: "default",
  },
  parameters: {
    controls: {
      exclude: ["type", "isHtml"],
    },
  },
  render: ({ ...args }) => <ContextNotification {...args} />,
};

export const Base = {};

export const WithLongerText = {
  args: {
    children:
      "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Praesent lorem ante, bibendum sed ex.",
  },
  parameters: {
    controls: { exclude: ["isHtml"] },
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
    controls: { exclude: ["level", "isHtml"] },
  },
};

export const Info = {
  args: {
    level: "info",
  },
  parameters: {
    controls: { exclude: ["level", "isHtml"] },
  },
};

export const Error = {
  args: {
    level: "error",
  },
  parameters: {
    controls: { exclude: ["level", "isHtml"] },
  },
};

export const Warning = {
  args: {
    level: "warning",
  },
  parameters: {
    controls: { exclude: ["level", "isHtml"] },
  },
};

export const Success = {
  args: {
    level: "success",
  },
  parameters: {
    controls: { exclude: ["level", "isHtml"] },
  },
};

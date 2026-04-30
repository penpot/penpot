// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { NotificationPill } from "./NotificationPill";

const meta = {
  title: "Notifications/NotificationPill",
  component: NotificationPill,
  args: {
    level: "info",
    type: "context",
    children: "This is a notification message.",
  },
} satisfies Meta<typeof NotificationPill>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Warning: Story = {
  args: { level: "warning" },
};

export const Error: Story = {
  args: { level: "error" },
};

export const Success: Story = {
  args: { level: "success" },
};

export const LevelDefault: Story = {
  args: { level: "default" },
};

export const Toast: Story = {
  args: { type: "toast" },
};

export const Ghost: Story = {
  args: { appearance: "ghost" },
};

export const WithDetail: Story = {
  args: {
    detail: "<pre>Stack trace line 1\nStack trace line 2</pre>",
    showDetail: false,
  },
};

export const WithDetailExpanded: Story = {
  args: {
    detail: "<pre>Stack trace line 1\nStack trace line 2</pre>",
    showDetail: true,
  },
};

export const HtmlContent: Story = {
  args: {
    isHtml: true,
    children: "Visit <a href='#'>this link</a> for details.",
  },
};

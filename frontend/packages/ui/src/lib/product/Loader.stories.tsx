// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Loader } from "./Loader";

const meta = {
  title: "Product/Loader",
  component: Loader,
  args: {
    overlay: false,
    fileLoading: false,
  },
  argTypes: {
    title: { control: "text" },
    width: { control: "number" },
    height: { control: "number" },
    overlay: { control: "boolean" },
    fileLoading: { control: "boolean" },
  },
} satisfies Meta<typeof Loader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithContent: Story = {
  args: {
    children: "Lorem ipsum",
  },
};

export const Overlay: Story = {
  args: {
    overlay: true,
    children: "Lorem ipsum",
  },
  parameters: {
    layout: "fullscreen",
  },
};

export const CustomSize: Story = {
  args: {
    width: 200,
  },
};

export const FileLoading: Story = {
  args: {
    fileLoading: true,
  },
  parameters: {
    layout: "fullscreen",
  },
};

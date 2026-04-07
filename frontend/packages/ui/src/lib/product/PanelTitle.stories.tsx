// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { PanelTitle } from "./PanelTitle";

const meta = {
  title: "Product/PanelTitle",
  component: PanelTitle,
  args: {
    text: "Lorem ipsum",
  },
  argTypes: {
    text: { control: "text" },
  },
} satisfies Meta<typeof PanelTitle>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithCloseButton: Story = {
  args: {
    onClose: () => {
      console.warn("close");
    },
  },
};

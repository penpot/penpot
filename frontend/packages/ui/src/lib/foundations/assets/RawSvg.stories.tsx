// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { rawSvgIds, RawSvg } from "./RawSvg";

const meta = {
  title: "Foundations/Assets/RawSvg",
  component: RawSvg,
  args: {
    id: "brand-gitlab",
    width: 200,
  },
  argTypes: {
    id: {
      options: rawSvgIds,
      control: { type: "select" },
    },
  },
} satisfies Meta<typeof RawSvg>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const PenpotLogo: Story = {
  args: { id: "penpot-logo", width: 200, height: 48 },
};

export const PenpotLogoIcon: Story = {
  args: { id: "penpot-logo-icon", width: 48, height: 48 },
};

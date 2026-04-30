/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import type { Meta, StoryObj } from "@storybook/react-vite";

import { Heading } from "./Heading";
import { typographyIds } from "./typography";

const meta = {
  title: "Foundations/Typography/Heading",
  component: Heading,
  argTypes: {
    level: {
      options: [1, 2, 3, 4, 5, 6],
      control: { type: "select" },
    },
    typography: {
      options: typographyIds,
      control: { type: "select" },
    },
  },
  parameters: {
    controls: { exclude: ["children"] },
  },
  args: {
    children: "Lorem ipsum",
  },
  render: ({ children, ...args }) => <Heading {...args}>{children}</Heading>,
} satisfies Meta<typeof Heading>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {
    level: 1,
    typography: "display",
  },
};

export const Level2: Story = {
  args: {
    level: 2,
    typography: "title-large",
    children: "Title Large h2",
  },
};

export const Level3: Story = {
  args: {
    level: 3,
    typography: "title-medium",
    children: "Title Medium h3",
  },
};

export const Level4: Story = {
  args: {
    level: 4,
    typography: "headline-large",
    children: "Headline Large h4",
  },
};

export const Level5: Story = {
  args: {
    level: 5,
    typography: "headline-medium",
    children: "Headline Medium h5",
  },
};

export const Level6: Story = {
  args: {
    level: 6,
    typography: "headline-small",
    children: "Headline Small h6",
  },
};

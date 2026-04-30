/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import type { Meta, StoryObj } from "@storybook/react-vite";

import { Text } from "./Text";
import { typographyIds } from "./typography";

const meta = {
  title: "Foundations/Typography/Text",
  component: Text,
  argTypes: {
    typography: {
      options: typographyIds,
      control: { type: "select" },
    },
    as: {
      control: { type: "text" },
    },
  },
  parameters: {
    controls: { exclude: ["children"] },
  },
  args: {
    children: "Lorem ipsum",
  },
  render: ({ children, ...args }) => <Text {...args}>{children}</Text>,
} satisfies Meta<typeof Text>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {
    typography: "display",
  },
};

export const CustomTag: Story = {
  args: {
    typography: "display",
    as: "li",
  },
};

export const Display: Story = {
  args: {
    typography: "display",
    children: "Display 400 36px/1.4 Work Sans",
  },
};

export const TitleLarge: Story = {
  args: {
    typography: "title-large",
    children: "Title Large 400 24px/1.4 Work Sans",
  },
};

export const TitleMedium: Story = {
  args: {
    typography: "title-medium",
    children: "Title Medium 400 20px/1.4 Work Sans",
  },
};

export const TitleSmall: Story = {
  args: {
    typography: "title-small",
    children: "Title Small 400 14px/1.2 Work Sans",
  },
};

export const HeadlineLarge: Story = {
  args: {
    typography: "headline-large",
    children: "Headline Large 400 18px/1.4 Work Sans",
  },
};

export const HeadlineMedium: Story = {
  args: {
    typography: "headline-medium",
    children: "Headline Medium 400 16px/1.4 Work Sans",
  },
};

export const HeadlineSmall: Story = {
  args: {
    typography: "headline-small",
    children: "Headline Small 500 12px/1.2 Work Sans",
  },
};

export const BodyLarge: Story = {
  args: {
    typography: "body-large",
    children: "Body Large 400 16px/1.4 Work Sans",
  },
};

export const BodyMedium: Story = {
  args: {
    typography: "body-medium",
    children: "Body Medium 400 14px/1.3 Work Sans",
  },
};

export const BodySmall: Story = {
  args: {
    typography: "body-small",
    children: "Body Small 400 12px/1.3 Work Sans",
  },
};

export const CodeFont: Story = {
  args: {
    typography: "code-font",
    children: "Code Font 400 12px/1.2 Roboto Mono",
  },
};

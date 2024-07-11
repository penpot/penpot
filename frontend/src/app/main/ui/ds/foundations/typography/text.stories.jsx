import * as React from "react";
import Components from "@target/components";

const { Text } = Components;
const { StoryWrapper } = Components.storybook;
const { typography } = Components.meta;

const typographyIds = typography.sort();

export default {
  title: "Foundations/Typography/Text",
  component: Text,
  argTypes: {
    typography: {
      options: typographyIds,
      control: { type: "select" },
    },
  },
};

export const Default = {
  render: ({ typography, ...args }) => (
    <StoryWrapper theme="default">
      <Text typography={typography} {...args}>
        Lorem ipsum
      </Text>
    </StoryWrapper>
  ),
  args: {
    typography: "display",
  },
};

export const CustomTag = {
  render: ({ typography, ...args }) => (
    <StoryWrapper theme="default">
      <Text typography={typography} {...args}>
        Lorem ipsum
      </Text>
    </StoryWrapper>
  ),
  args: {
    typography: "display",
    as: "li",
  },
};

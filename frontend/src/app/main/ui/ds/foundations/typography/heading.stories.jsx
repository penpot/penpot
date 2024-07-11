import * as React from "react";
import Components from "@target/components";

const { Heading } = Components;
const { StoryWrapper } = Components.storybook;
const { typography } = Components.meta;

const typographyIds = typography.sort();

export default {
  title: "Foundations/Typography/Heading",
  component: Components.Heading,
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
};

export const AnyHeading = {
  name: "Heading",
  render: ({ level, typography, ...args }) => (
    <StoryWrapper theme="default">
      <Heading level={level} typography={typography} {...args}>
        Lorem ipsum
      </Heading>
    </StoryWrapper>
  ),
  args: {
    level: 1,
    typography: "display",
  },
};

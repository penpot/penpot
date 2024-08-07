import * as React from "react";
import Components from "@target/components";

const { Heading } = Components;
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
  parameters: {
    controls: { exclude: ["children", "theme", "style"] },
    backgrounds: { default: "light" },
  },
  args: {
    children: "Lorem ipsum",
    theme: "light",
    style: {
      color: "var(--color-foreground-primary)",
      background: "var(--color-background-primary)",
    },
  },
  render: ({ style, children, theme, ...args }) => (
    <Heading {...args}>{children}</Heading>
  ),
};

export const AnyHeading = {
  name: "Heading",
  args: {
    level: 1,
    typography: "display",
  },
};

import * as React from "react";
import Components from "@target/components";

const { Text } = Components;
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
    // TODO: this <div> is a hack until we have proper theming
    <div style={style} className={theme}>
      <Text {...args}>{children}</Text>
    </div>
  ),
};

export const Default = {
  args: {
    typography: "display",
  },
};

export const CustomTag = {
  args: {
    typography: "display",
    as: "li",
  },
};

export const Display = {
  args: {
    typography: "display",
    children: "Display 400 36px/1.4 Work Sans",
  },
};

export const TitleLarge = {
  args: {
    typography: "title-large",
    children: "Title Large 400 24px/1.4 Work Sans",
  },
};

export const TitleMedium = {
  args: {
    typography: "title-medium",
    children: "Title Medium 400 20px/1.4 Work Sans",
  },
};

export const TitleSmall = {
  args: {
    typography: "title-small",
    children: "Title Small 400 14px/1.2 Work Sans",
  },
};

export const HeadlineLarge = {
  args: {
    typography: "headline-large",
    children: "Headline Large 400 18px/1.4 Work Sans",
  },
};

export const HeadlineMedium = {
  args: {
    typography: "headline-medium",
    children: "Headline Medium 400 16px/1.4 Work Sans",
  },
};

export const HeadlineSmall = {
  args: {
    typography: "headline-small",
    children: "Headline Small 500 12px/1.2 Work Sans",
  },
};

export const BodyLarge = {
  args: {
    typography: "body-large",
    children: "Body Large 400 16px/1.4 Work Sans",
  },
};

export const BodyMedium = {
  args: {
    typography: "body-medium",
    children: "Body Medium 400 14px/1.3 Work Sans",
  },
};

export const BodySmall = {
  args: {
    typography: "body-small",
    children: "Body Small 400 12px/1.3 Work Sans",
  },
};

export const CodeFont = {
  args: {
    typography: "code-font",
    children: "Code Font 400 12px/1.2 Roboto Mono",
  },
};

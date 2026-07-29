import * as React from "react";
import Components from "@target/components";

const { Icon } = Components;
const { StoryGrid, StoryGridCell, StoryHeader } = Components.storybook;
const { icons } = Components.meta;

export default {
  title: "Foundations/Assets/Icon",
  component: Components.Icon,
  argTypes: {
    iconId: {
      options: icons,
      control: { type: "select" },
    },
    size: {
      options: ["m", "s"],
      control: { type: "radio" },
    },
  },
  render: ({ ...args }) => <Icon {...args} />,
};

export const All = {
  render: ({ size }) => (
    <>
      <StoryHeader>
        <h1>All Icons</h1>
      </StoryHeader>
      <StoryGrid size="256">
        {icons.map((iconId) => (
          <StoryGridCell
            key={iconId}
            style={{
              color: "var(--color-accent-primary)",
              display: "grid",
              gap: "0.5rem",
            }}
          >
            <Icon iconId={iconId} size={size} />
            <code
              style={{
                fontFamily: "monospace",
                color: "var(--color-foreground-secondary)",
              }}
            >
              {iconId}
            </code>
          </StoryGridCell>
        ))}
      </StoryGrid>
    </>
  ),
  args: {
    size: "m",
  },
  parameters: {
    controls: { exclude: ["iconId", "size"] },
    backgrounds: { disabled: true },
  },
};

export const Default = {
  args: {
    iconId: "pin",
  },
  parameters: {
    controls: { exclude: ["size"] },
  },
};

export const CustomSize = {
  args: {
    iconId: "pin",
    size: "m",
  },
};

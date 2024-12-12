import * as React from "react";
import Components from "@target/components";

const { Icon } = Components;
const { StoryGrid, StoryGridCell, StoryHeader } = Components.storybook;
const { icons } = Components.meta;

export default {
  title: "Foundations/Assets/Icon",
  component: Components.Icon,
  argTypes: {
    id: {
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
            <Icon id={iconId} size={size} />
            <code
              style={{
                "font-family": "monospace",
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
    controls: { exclude: ["id", "size"] },
    backgrounds: { disable: true },
  },
};

export const Default = {
  args: {
    id: "pin",
  },
  parameters: {
    controls: { exclude: ["size"] },
  },
};

export const CustomSize = {
  args: {
    id: "pin",
    size: "m",
  },
};

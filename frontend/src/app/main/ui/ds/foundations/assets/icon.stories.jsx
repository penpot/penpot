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
        <p>Hover on an icon to see its ID.</p>
      </StoryHeader>
      <StoryGrid>
        {icons.map((iconId) => (
          <StoryGridCell
            title={iconId}
            key={iconId}
            style={{ color: "var(--color-accent-primary)" }}
          >
            <Icon id={iconId} size={size} />
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

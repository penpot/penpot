import * as React from "react";
import Components from "@target/components";

const { Icon } = Components;
const { StoryWrapper, StoryGrid, StoryGridCell, StoryHeader } =
  Components.storybook;
const { icons } = Components.meta;

const iconList = Object.entries(icons)
  .map(([_, value]) => value)
  .sort();

export default {
  title: "Foundations/Assets/Icon",
  component: Components.Icon,
  argTypes: {
    id: {
      options: iconList,
      control: { type: "select" },
    },
    size: {
      options: ["m", "s"],
      control: { type: "radio" },
    },
  },
};

export const All = {
  render: ({ size }) => (
    <StoryWrapper theme="default">
      <StoryHeader>
        <h1>All Icons</h1>
        <p>Hover on an icon to see its ID.</p>
      </StoryHeader>
      <StoryGrid>
        {iconList.map((iconId) => (
          <StoryGridCell
            title={iconId}
            key={iconId}
            style={{ color: "var(--color-accent-primary)" }}
          >
            <Icon id={iconId} size={size} />
          </StoryGridCell>
        ))}
      </StoryGrid>
    </StoryWrapper>
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
  render: ({ id, ...args }) => (
    <StoryWrapper theme="default">
      <Icon id={id} {...args} />
    </StoryWrapper>
  ),
  args: {
    id: "pin",
  },
  parameters: {
    controls: { exclude: ["size"] },
  },
};

export const CustomSize = {
  render: ({ id, size, ...args }) => (
    <StoryWrapper theme="default">
      <Icon id={id} size={size} {...args} />
    </StoryWrapper>
  ),
  args: {
    id: "pin",
    size: "m",
  },
};

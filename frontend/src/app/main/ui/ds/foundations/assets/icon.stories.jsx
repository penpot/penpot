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
    icon: {
      options: iconList,
      control: { type: "select" }
    },
    size: {
      options: ["m", "s"],
      control: { type: "radio" }
    }
  }
};

export const AllIcons = {
  render: () => (
    <StoryWrapper theme="default">
      <StoryHeader>
        <h1>All Icons</h1>
        <p>Hover on an icon to see its ID</p>
      </StoryHeader>
      <StoryGrid>
        {iconList.map((iconId) => (
          <StoryGridCell
            title={iconId}
            key={iconId}
            style={{ color: "var(--color-accent-primary)" }}
          >
            <Icon icon={iconId} />
          </StoryGridCell>
        ))}
      </StoryGrid>
    </StoryWrapper>
  ),
  parameters: {
    backgrounds: { disable: true },
  },
};

export const Default = {
  render: ({icon, ...args}) => (
    <StoryWrapper theme="default">
      <Icon icon={icon} />
    </StoryWrapper>
  ),
  args: {
    icon: "pin",
  },
  parameters: {
    controls: { exclude: "size" }
  }
};

export const CustomSize = {
  render: ({icon, size, ...args}) => (
    <StoryWrapper theme="default">
      <Icon icon={icon} size={size} />
    </StoryWrapper>
  ),
  args: {
    icon: "pin",
    size: "m",
  }
};


import * as React from "react";
import Components from "@target/components";

const { Icon } = Components;
const { StoryWrapper, StoryGrid, StoryGridCell, StoryHeader } =
  Components.storybook;
const { icons } = Components.meta;

export default {
  title: "Foundations/Icons",
  component: Components.Icon,
};

const iconList = Object.entries(icons)
  .map(([_, value]) => value)
  .sort();

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
  render: () => (
    <StoryWrapper theme="default">
      <Icon icon={icons.Pin} />
    </StoryWrapper>
  ),
};

export const Small = {
  render: () => (
    <StoryWrapper theme="default">
      <Icon icon={icons.Pin} size="s" />
    </StoryWrapper>
  ),
};

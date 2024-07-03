import * as React from "react";
import Components from "@target/components";

const { Icon } = Components;
const { StoryWrapper, IconGrid } = Components.storybook;
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
      <h1>All Icons</h1>
      <p>Hover on an icon to see its ID</p>
      <IconGrid>
        {iconList.map((iconId) => (
          <div title={iconId} key={iconId}>
            <Icon icon={iconId} />
          </div>
        ))}
      </IconGrid>
    </StoryWrapper>
  ),
};

export const Default = {
  render: () => (
    <StoryWrapper theme="default">
      <Icon icon="pin" />
    </StoryWrapper>
  ),
};

export const Small = {
  render: () => (
    <StoryWrapper theme="default">
      <Icon icon="pin" size="s" />
    </StoryWrapper>
  ),
};

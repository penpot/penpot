import * as React from "react";
import Components from "@target/components";

const { RawSvg } = Components;
const { StoryWrapper, StoryGrid, StoryGridCell, StoryHeader } =
  Components.storybook;
const { svgs } = Components.meta;

const assetList = Object.entries(svgs)
  .map(([_, value]) => value)
  .sort();

export default {
  title: "Foundations/Assets/RawSvg",
  component: Components.RawSvg,
  argTypes: {
    id: {
      options: assetList,
      control: { type: "select" },
    },
  },
};

export const All = {
  render: ({}) => (
    <StoryWrapper theme="light">
      <StoryHeader>
        <h1>All SVG Assets</h1>
        <p>Hover on an asset to see its ID.</p>
      </StoryHeader>

      <StoryGrid size="200">
        {assetList.map((x) => (
          <StoryGridCell key={x} title={x}>
            <RawSvg id={x} style={{ maxWidth: "100%" }} />
          </StoryGridCell>
        ))}
      </StoryGrid>
    </StoryWrapper>
  ),
  parameters: {
    controls: { exclude: ["id"] },
    backgrounds: { values: [{ name: "debug", value: "#ccc" }] },
  },
};

export const Default = {
  render: ({ id, ...args }) => (
    <StoryWrapper theme="default">
      <RawSvg id={id} {...args} width="200" />
    </StoryWrapper>
  ),
  args: {
    id: "brand-gitlab",
  },
};

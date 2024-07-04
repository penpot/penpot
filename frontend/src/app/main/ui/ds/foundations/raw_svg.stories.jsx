import * as React from "react";
import Components from "@target/components";

const { RawSvg } = Components;
const { StoryWrapper, StoryGrid } = Components.storybook;
const { svgs } = Components.meta;

export default {
  title: "Foundations/RawSvg",
  component: Components.RawSvg,
};

const assetList = Object.entries(svgs)
  .map(([_, value]) => value)
  .sort();

export const AllAssets = {
  render: () => (
    <StoryWrapper theme="default">
      <h1>All assets</h1>
      <p>Hover on a asset to see its id.</p>

      <StoryGrid size="200">
        {assetList.map(x => (
          <div key={x} title={x}>
            <RawSvg asset={x} style={{maxWidth: "100%"}} />
          </div>
        ))}
      </StoryGrid>
    </StoryWrapper>
  ),
  parameters: {
    backgrounds: { default: "debug" }
  }
}

export const Default = {
  render: () => (
    <StoryWrapper theme="default">
      <RawSvg asset="brand-gitlab" width="200" />
    </StoryWrapper>
  ),
}

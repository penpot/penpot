import * as React from "react";
import Components from "@target/components";

const { RawSvg } = Components;
const { StoryWrapper, StoryGrid, StoryGridCell, StoryHeader } =
  Components.storybook;
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
    <StoryWrapper theme="light">
      <StoryHeader>
        <h1>All assets</h1>
        <p>Hover on a asset to see its id.</p>
      </StoryHeader>

      <StoryGrid size="200">
        {assetList.map((x) => (
          <StoryGridCell key={x} title={x}>
            <RawSvg asset={x} style={{ maxWidth: "100%" }} />
          </StoryGridCell>
        ))}
      </StoryGrid>
    </StoryWrapper>
  ),
  parameters: {
    backgrounds: { values: [{ name: "debug", value: "#ccc" }] },
  },
};

export const Default = {
  render: () => (
    <StoryWrapper theme="default">
      <RawSvg asset={svgs.BrandGitlab} width="200" />
    </StoryWrapper>
  ),
};

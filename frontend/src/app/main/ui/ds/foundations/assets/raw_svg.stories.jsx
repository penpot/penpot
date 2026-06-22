import * as React from "react";
import Components from "@target/components";

const { RawSvg } = Components;
const { StoryGrid, StoryGridCell, StoryHeader } = Components.storybook;
const { svgs } = Components.meta;

export default {
  title: "Foundations/Assets/RawSvg",
  component: Components.RawSvg,
  argTypes: {
    id: {
      options: svgs,
      control: { type: "select" },
    },
  },
  render: ({ ...args }) => <RawSvg {...args} />,
};

export const All = {
  render: ({}) => (
    <>
      <StoryHeader>
        <h1>All SVG Assets</h1>
        <p>Hover on an asset to see its ID.</p>
      </StoryHeader>

      <StoryGrid size="200">
        {svgs.map((x) => (
          <StoryGridCell key={x} title={x}>
            <RawSvg id={x} style={{ maxWidth: "100%" }} />
          </StoryGridCell>
        ))}
      </StoryGrid>
    </>
  ),
  parameters: {
    controls: { exclude: ["id"] },
    backgrounds: {
      options: {
        debug: { name: "debug", value: "#ccc" },
      },
    },
  },
};

export const Default = {
  args: {
    id: "brand-gitlab",
    width: 200,
  },
};

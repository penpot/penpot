import * as React from "react";
import Components from "@target/components";

const { Loader } = Components;
const { StoryWrapper } = Components.storybook;

export default {
  title: "Product/Loader",
  component: Components.Loader,
};

export const Default = {
  render: () => (
    <StoryWrapper theme="default">
      <Loader title="Loading" />
    </StoryWrapper>
  ),
};

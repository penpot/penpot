import * as React from "react";
import Components from "@target/components";

const { Heading } = Components;
const { StoryWrapper, StoryGridRow } = Components.storybook;

export default {
  title: "Foundations/Heading",
  component: Components.Heading,
};

export const Levels = {
  render: () => (
    <StoryWrapper theme="default">
      <StoryGridRow title={"1 / display"}>
        <Heading level="1" typography="display">
          h1 / display
        </Heading>
      </StoryGridRow>
      <StoryGridRow title={"2 / display"}>
        <Heading level="2" typography="display">
          h2 / display
        </Heading>
      </StoryGridRow>
      <StoryGridRow title={"3 / display"}>
        <Heading level="3" typography="display">
          h3 / display
        </Heading>
      </StoryGridRow>
    </StoryWrapper>
  ),
};

export const HeadingTypography = {
  render: () => (
    <StoryWrapper theme="default">
      <StoryGridRow title={"1 / title-large"}>
        <Heading level="1" typography="title-large">
          h1 / title-large
        </Heading>
      </StoryGridRow>
      <StoryGridRow title={"1 / title-medium"}>
        <Heading level="1" typography="title-medium">
          h1 / title-medium
        </Heading>
      </StoryGridRow>
      <StoryGridRow title={"1 / code-font"}>
        <Heading level="1" typography="code-font">
          h1 / code-font
        </Heading>
      </StoryGridRow>
    </StoryWrapper>
  ),
};

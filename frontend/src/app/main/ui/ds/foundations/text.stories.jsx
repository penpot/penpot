import * as React from "react";
import Components from "@target/components";

const { Text } = Components;
const { StoryWrapper, StoryGridRow } = Components.storybook;

export default {
  title: "Foundations/Text",
  component: Components.Text,
};

export const TextTags = {
  render: () => (
    <StoryWrapper theme="default">
      <StoryGridRow title={"p / title-large"}>
        <Text tag="p" typography="title-large">
          p / Title
        </Text>
      </StoryGridRow>
      <StoryGridRow title={"span / title-large"}>
        <Text tag="span" typography="title-large">
          span / Title large
        </Text>
      </StoryGridRow>
      <StoryGridRow title={"div / title-large"}>
        <Text tag="div" typography="title-large">
          div / Title large
        </Text>
      </StoryGridRow>
    </StoryWrapper>
  ),
};

export const TypographyParagraph = {
  render: () => (
    <StoryWrapper theme="default">
      <StoryGridRow title={"p / title-large"}>
        <Text tag="p" typography="title-large">
          p / Title large
        </Text>
      </StoryGridRow>
      <StoryGridRow title={"p / title-medium"}>
        <Text tag="p" typography="title-medium">
          p / Title medium
        </Text>
      </StoryGridRow>
      <StoryGridRow title={"p / code-font"}>
        <Text tag="p" typography="code-font">
          p / Code font
        </Text>
      </StoryGridRow>
    </StoryWrapper>
  ),
};

import * as React from "react";
import Components from "@target/components";

const { Heading } = Components;
const { StoryWrapper, StoryHeader, StoryGridRow } = Components.storybook;

const typographyList = {
  display: {
    name: "Display",
    id: "display",
    size: "36px",
    weight: "400",
    line: "1.4",
    uppercase: false,
    font: "Work Sans",
  },
  titleLarge: {
    name: "Title large",
    id: "title-large",
    size: "24px",
    weight: "400",
    line: "1.4",
    uppercase: false,
    font: "Work Sans",
  },
  titleMedium: {
    name: "Title medium",
    id: "title-medium",
    size: "20px",
    weight: "400",
    line: "1.4",
    uppercase: false,
    font: "Work Sans",
  },
  titleSmall: {
    name: "Title small",
    id: "title-small",
    size: "14px",
    weight: "400",
    line: "1.2",
    uppercase: false,
    font: "Work Sans",
  },
  headlineLarge: {
    name: "Headline large",
    id: "headline-large",
    size: "18px",
    weight: "400",
    line: "1.4",
    uppercase: true,
    font: "Work Sans",
  },
  headlineMedium: {
    name: "Headline medium",
    id: "headline-medium",
    size: "16px",
    weight: "400",
    line: "1.4",
    uppercase: true,
    font: "Work Sans",
  },
  headlineSmall: {
    name: "Headline small",
    id: "headline-small",
    size: "12px",
    weight: "500",
    line: "1.2",
    uppercase: true,
    font: "Work Sans",
  },
  bodyLarge: {
    name: "Body large",
    id: "body-large",
    size: "16px",
    weight: "400",
    line: "1.4",
    uppercase: false,
    font: "Work Sans",
  },
  bodyMedium: {
    name: "Body medium",
    id: "body-medium",
    size: "14px",
    weight: "400",
    line: "1.3",
    uppercase: false,
    font: "Work Sans",
  },
  bodySmall: {
    name: "Body small",
    id: "body-small",
    size: "12px",
    weight: "400",
    line: "1.3",
    uppercase: false,
    font: "Work Sans",
  },
  codeFont: {
    name: "Code font",
    id: "code-font",
    size: "12px",
    weight: "400",
    line: "1.2",
    uppercase: false,
    font: "Roboto Mono",
  },
};

export default {
  title: "Foundations/Typography",
  component: Components.StoryHeader,
};

export const AllTypography = {
  render: () => (
    <StoryWrapper theme="default">
      <StoryHeader>
        <h1>All Typography</h1>
        <p>Hover on a heading to see its ID</p>
      </StoryHeader>
      {Object.values(typographyList).map(
        ({ id, name, size, weight, line, font }) => (
          <StoryGridRow title={id} key={id}>
            <Heading level="1" typography={id}>
              {name} - {weight} - {size}/{line} {font}
            </Heading>
          </StoryGridRow>
        ),
      )}
    </StoryWrapper>
  ),
  parameters: {
    backgrounds: { disable: true },
  },
};

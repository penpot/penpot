import * as React from "react";

import ds from "@target/design-system";

const { SimpleButton, StoryWrapper, icons } = ds;

export default {
  title: 'Buttons/Simple Button',
  component: SimpleButton,
};

export const Default = {
  render: () => (
    <StoryWrapper>
      <SimpleButton>
        Simple Button
      </SimpleButton>
    </StoryWrapper>
  ),
};

export const WithIcon = {
  render: () => (
    <StoryWrapper>
      <SimpleButton>
        {icons.IconAddRefactor}
        Simple Button
      </SimpleButton>
    </StoryWrapper>
  ),
}

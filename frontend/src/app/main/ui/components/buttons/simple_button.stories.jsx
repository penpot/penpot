import * as React from "react";

import Components from "@target/components";
import Icons from "@target/icons";

export default {
  title: 'Buttons/Simple Button',
  component: Components.SimpleButton,
};

export const Default = {
  render: () => (
    <Components.StoryWrapper>
      <Components.SimpleButton>
        Simple Button
      </Components.SimpleButton>
    </Components.StoryWrapper>
  ),
};

export const WithIcon = {
  render: () => (
    <Components.StoryWrapper>
      <Components.SimpleButton>
        {Icons.AddRefactor}
        Simple Button
      </Components.SimpleButton>
    </Components.StoryWrapper>
  ),
}

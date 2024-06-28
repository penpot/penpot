import * as React from "react";

import Components from "@target/components";

export default {
  title: "Buttons/Simple Button",
  component: Components.SimpleButton,
};

export const Default = {
  render: () => (
    <Components.StoryWrapper>
      <Components.SimpleButton>Simple Button</Components.SimpleButton>
    </Components.StoryWrapper>
  ),
};

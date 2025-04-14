import * as React from "react";
import Components from "@target/components";

const { InputTokens } = Components;

export default {
  title: "Product/InputTokens",
  component: InputTokens,
  args: {
    id: "input-tokens",
    label: "Input tokens label",
  },
  render: ({ ...args }) => (
    <InputTokens {...args}>
      
    </InputTokens>
  ),
};

export const Default = {};

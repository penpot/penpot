import * as React from "react";
import Components from "@target/components";

const { InputTokens } = Components;

export default {
  title: "Product/InputTokens",
  component: InputTokens,
  argTypes: {
    label: {
      control: { type: "text" },
    },
    isOptional: { control: { type: "boolean" }  },
    placeholder:{ control: { type: "text" }  },
    maxLength:{ control: { type: "number" }  },
    error:{ control: { type: "text" }  },
    value:{ control: { type: "text" }  },
  },
  args: {
    id: "input-tokens",
    label: "Input tokens label",
    isOptional: true,
    placeholder: "Placeholder",
    maxLength: 255,
  },
  parameters: {
    controls: { exclude: ["id"] },
  },
  render: ({ ...args }) => (
    <InputTokens {...args}></InputTokens>
  ),
};

export const Default = {};

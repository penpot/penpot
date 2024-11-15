import * as React from "react";
import Components from "@target/components";

const { EmptyPlaceholder } = Components;

export default {
  title: "Product/EmptyPlaceholder",
  component: EmptyPlaceholder,
  argTypes: {
    title: {
      control: { type: "text" },
    },
    type: {
      control: "radio",
      options: [1, 2],
    },
  },
  args: {
    type: 1,
    title: "Lorem ipsum",
    subtitle:
      "dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.",
  },
  render: ({ ...args }) => <EmptyPlaceholder {...args} />,
};

export const Default = {};

export const AlternativeDecoration = {
  args: {
    type: 2,
  },
};

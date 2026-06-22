import * as React from "react";
import Components from "@target/components";

const { PanelTitle } = Components;

export default {
  title: "Product/PanelTitle",
  component: PanelTitle,
  argTypes: {
    text: {
      control: { type: "text" },
    },
  },
  args: {
    text: "Lorem ipsum",
    onClose: () => null,
  },
  render: ({ ...args }) => <PanelTitle {...args} />,
};

export const Default = {};

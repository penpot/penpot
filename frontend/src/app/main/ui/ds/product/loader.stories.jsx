import * as React from "react";
import Components from "@target/components";

const { Loader } = Components;

export default {
  title: "Product/Loader",
  component: Loader,
  args: {
    overlay: false,
  },
  argTypes: {
    title: { control: "text" },
    width: { control: "number" },
    height: { control: "number" },
    overlay: { control: "boolean" },
    children: { control: "text" },
  },
  render: ({ ...args }) => <Loader {...args} />,
};

export const Default = {};

export const WithContent = {
  args: {
    children: "Lorem ipsum",
  },
};

export const Overlay = {
  args: {
    overlay: true,
    children: "Lorem ipsum",
  },
  parameters: {
    layout: "fullscreen",
  },
};

export const Inline = {
  args: {
    children: "Lorem ipsum",
  },
};

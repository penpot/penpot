import * as React from "react";
import Components from "@target/components";

const { Loader } = Components;

export default {
  title: "Product/Loader",
  component: Loader,
  args: {
    title: "Loading",
    overlay: false,
  },
  parameters: {
    controls: { exclude: ["theme", "style", "title", "overlay"] },
  },
  render: ({ children, ...args }) => <Loader {...args}>{children}</Loader>,
};

export const Default = {};

export const Overlay = {
  args: {
    overlay: true,
    style: { height: "100vh" },
  },
};

export const Inline = {
  args: {
    children: "Loading...",
    width: "16px",
    height: "24px",
  },
};

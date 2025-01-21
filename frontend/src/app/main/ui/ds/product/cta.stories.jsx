import * as React from "react";
import Components from "@target/components";

const { Cta } = Components;

export default {
  title: "Product/CTA",
  component: Cta,
  argTypes: {
    title: {
      control: { type: "text" },
    },
  },
  args: {
    title: "Autosaved versions will be kept for 7 days.",
  },
  render: ({ ...args }) => (
    <Cta {...args}>
      If you’d like to increase this limit, write to us at{" "}
      <a href="#">support@penpot.app</a>
    </Cta>
  ),
};

export const Default = {};

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
      <span
        style={{
          fontSize: "0.75rem",
          color: "var(--color-foreground-secondary)",
        }}
      >
        If you’d like to increase this limit, write to us at{" "}
        <a style={{ color: "var(--color-accent-primary)" }} href="#">
          support@penpot.app
        </a>
      </span>
    </Cta>
  ),
};

export const Default = {};

import * as React from "react";
import Components from "@target/components";

const { Actionable } = Components;

export default {
  title: "Notifications/Actionable",
  component: Actionable,
  argTypes: {
    variant: {
      options: ["default", "error"],
      control: { type: "select" },
    },
    acceptLabel: {
      control: { type: "text" },
    },
    cancelLabel: {
      control: { type: "text" },
    },
  },
  args: {
    variant: "default",
    acceptLabel: "Update",
    cancelLabel: "Dismiss",
  },
  render: ({ ...args }) => (
    <Actionable {...args}>
      Message for the notification <a href="#">more info</a>
    </Actionable>
  ),
};

export const Default = {};

export const Error = {
  args: {
    variant: "error",
  },
};

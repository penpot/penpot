import * as React from "react";
import Components from "@target/components";

const { Date } = Components;

export default {
  title: "Foundations/Utilities/Date",
  component: Date,
  argTypes: {
    date: {
      control: { type: "date" },
    },
    selected: {
      control: { type: "boolean" },
    },
  },
  args: {
    title: "Date",
    date: 1735686000000,
    selected: false,
  },
  render: ({ ...args }) => <Date {...args} />,
};

export const Default = {};

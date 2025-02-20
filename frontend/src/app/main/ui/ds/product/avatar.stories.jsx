import * as React from "react";
import Components from "@target/components";

const { Avatar } = Components;

export default {
  title: "Product/Avatar",
  component: Avatar,
  argTypes: {
    name: {
      control: { type: "text" },
    },
    url: {
      control: { type: "text" },
    },
    color: {
      control: { type: "color" },
    },
    variant: {
      options: ["S", "M", "L"],
      control: { type: "select" },
    },
    selected: {
      control: { type: "boolean" },
    },
  },
  args: {
    name: "Ada Lovelace",
    url: "/images/avatar-blue.jpg",
    color: "#79d4ff",
    variant: "S",
    selected: false,
  },
  render: ({ ...args }) => <Avatar profile={{ fullname: "TEST" }} {...args} />,
};

export const Default = {};

export const NoURL = {
  args: {
    url: null,
  },
};

export const Small = {
  args: {
    variant: "S",
  },
};

export const Medium = {
  args: {
    variant: "M",
  },
};

export const Large = {
  args: {
    variant: "L",
  },
};

export const Selected = {
  args: {
    selected: true,
  },
};

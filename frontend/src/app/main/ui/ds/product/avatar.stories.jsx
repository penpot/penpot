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
    variant: "S",
    selected: false,
  },
  render: ({ name, url, ...args }) => {
    const profile = {
      id: "00000000-0000-0000-0000-000000000000",
      fullname: name,
    };
    if (url) {
      profile.photoUrl = url;
    }

    return <Avatar profile={profile} {...args} />;
  },
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

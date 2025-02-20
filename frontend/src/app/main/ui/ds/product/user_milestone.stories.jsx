import * as React from "react";
import Components from "@target/components";

const { UserMilestone } = Components;

export default {
  title: "Product/Milestones/User",
  component: UserMilestone,

  argTypes: {
    userName: {
      control: { type: "text" },
    },
    userAvatar: {
      control: { type: "text" },
    },
    userColor: {
      control: { type: "color" },
    },
    label: {
      control: { type: "text" },
    },
    date: {
      control: { type: "date" },
    },
    active: {
      control: { type: "boolean" },
    },
    editing: {
      control: { type: "boolean" },
    },
    autosaved: {
      control: { type: "boolean" },
    },
    versionToggled: {
      control: { type: "boolean" },
    },
    snapshots: {
      control: { type: "object" },
    },
  },
  args: {
    label: "Milestone 1",
    userName: "Ada Lovelace",
    userAvatar: "/images/avatar-blue.jpg",
    userColor: "#79d4ff",
    date: 1735686000000,
    active: false,
    editing: false,
  },
  render: ({ ...args }) => {
    const user = {
      name: args.userName,
      avatar: args.userAvatar,
      color: args.userColor,
    };
    return <UserMilestone user={user} {...args} />;
  },
};

export const Default = {};

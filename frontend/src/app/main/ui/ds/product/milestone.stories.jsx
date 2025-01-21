import * as React from "react";
import Components from "@target/components";

const { Milestone } = Components;

export default {
  title: "Product/Milestone",
  component: Milestone,

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
    autosaved: false,
    versionToggled: false,
    snapshots: [1737452413841, 1737452422063, 1737452431603],
    autosavedMessage: "3 autosave versions",
  },
  render: ({ ...args }) => <Milestone {...args} />,
};

export const Default = {};

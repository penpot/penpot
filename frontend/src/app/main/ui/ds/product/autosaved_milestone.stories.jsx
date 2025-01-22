import * as React from "react";
import Components from "@target/components";

const { AutosavedMilestone } = Components;

export default {
  title: "Product/Milestones/Autosaved",
  component: AutosavedMilestone,

  argTypes: {
    label: {
      control: { type: "text" },
    },
    active: {
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
    active: false,
    versionToggled: false,
    snapshots: [1737452413841, 1737452422063, 1737452431603],
    autosavedMessage: "3 autosave versions",
  },
  render: ({ ...args }) => {
    const user = {
      name: args.userName,
      avatar: args.userAvatar,
      color: args.userColor,
    };
    return <AutosavedMilestone user={user} {...args} />;
  },
};

export const Default = {};

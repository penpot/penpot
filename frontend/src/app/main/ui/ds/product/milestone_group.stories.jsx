import * as React from "react";
import Components from "@target/components";

const { MilestoneGroup } = Components;

export default {
  title: "Product/Milestones/MilestoneGroup",
  component: MilestoneGroup,

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
    snapshots: [1737452413841, 1737452422063, 1737452431603],
  },
  render: ({ ...args }) => {
    return <MilestoneGroup {...args} />;
  },
};

export const Default = {};

import * as React from "react";
import Components from "@target/components";

const { Milestone } = Components;

export default {
  title: "Product/Milestones/Milestone",
  component: Milestone,

  argTypes: {
    profileName: {
      control: { type: "text" },
    },
    profileAvatar: {
      control: { type: "text" },
    },
    label: {
      control: { type: "text" },
    },
    createdAt: {
      control: { type: "date" },
    },
    active: {
      control: { type: "boolean" },
    },
    editing: {
      control: { type: "boolean" },
    },
    locked: {
      control: { type: "boolean" },
    },
  },
  args: {
    label: "Milestone 1",
    profileName: "Ada Lovelace",
    profileAvatar: "/images/avatar-blue.jpg",
    createdAt: 1735686000000,
    active: false,
    editing: false,
  },
  render: ({
    profileName,
    profileAvatar,
    profileColor,
    createdAt,
    ...args
  }) => {
    const profile = {
      id: "00000000-0000-0000-0000-000000000000",
      fullname: profileName,
    };

    if (profileAvatar) {
      profile.photoUrl = profileAvatar;
    }

    if (createdAt instanceof Number) {
      createdAt = new Date(createdAt);
    }

    return <Milestone profile={profile} createdAt={createdAt} {...args} />;
  },
};

export const Default = {};

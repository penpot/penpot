// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { RadioCards } = Components;

const options = [
  {
    id: "verdict-approved",
    value: "approved",
    icon: "tick",
    label: "Approve",
    description: "The changes look good to you",
  },
  {
    id: "verdict-changes",
    value: "changes-requested",
    icon: "triangle-alert",
    label: "Request changes",
    description: "Something needs to be fixed before merging",
  },
];

const optionsDisabled = [
  {
    id: "mode-merge",
    value: "merge",
    icon: "git-merge",
    label: "Merge and delete the branch",
    description: "Main gets the changes. The branch and its file are deleted.",
  },
  {
    id: "mode-apply",
    value: "apply",
    icon: "git-branch",
    label: "Apply and keep the branch",
    description: "Update this branch from main before you apply its changes.",
    disabled: true,
  },
];

const RadioCardsWrapper = ({ selected: initial, ...props }) => {
  const [selected, setSelected] = React.useState(initial);
  return (
    <div style={{ inlineSize: "420px" }}>
      <RadioCards
        {...props}
        selected={selected}
        onChange={(value) => setSelected(value)}
      />
    </div>
  );
};

export default {
  title: "Controls/Radio Cards",
  component: RadioCards,
  argTypes: {
    name: {
      control: { type: "text" },
      description: "The name that groups the native radios",
    },
    selected: {
      control: { type: "text" },
      description: "The value picked",
    },
  },
  args: {
    name: "radio-cards",
    selected: "approved",
    "aria-label": "Your review",
    options: options,
  },
  render: (args) => <RadioCardsWrapper {...args} />,
};

export const Default = {};

export const WithDisabledOption = {
  args: {
    name: "radio-cards-disabled",
    selected: "merge",
    "aria-label": "What happens to the branch",
    options: optionsDisabled,
  },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { TabSwitcher } = Components;

const Padded = ({ children }) => (
  <div style={{ padding: "10px" }}>{children}</div>
);

const TabSwitcherWrapper = ({ tabs, ...props }) => {
  const navTabs = tabs.map(({ content, ...item }) => {
    return item;
  });

  const [selected, setSelected] = React.useState(() => {
    return props.default || tabs[0].id;
  });

  const content = tabs.reduce((result, tab) => {
    result[tab.id] = tab.content;
    return result;
  }, {});

  return (
    <TabSwitcher
      tabs={navTabs}
      selected={selected}
      onChange={setSelected}
      {...props}
    >
      {content[selected]}
    </TabSwitcher>
  );
};

export default {
  title: "Layout/Tab switcher",
  component: TabSwitcherWrapper,
  args: {
    tabs: [
      {
        label: "Code",
        id: "tab-code",
        content: (
          <Padded>
            <p>Lorem Ipsum</p>
          </Padded>
        ),
      },
      {
        label: "Design",
        id: "tab-design",
        content: (
          <Padded>
            <p>Dolor sit amet</p>
          </Padded>
        ),
      },
      {
        label: "Menu",
        id: "tab-menu",
        content: (
          <Padded>
            <p>Consectetur adipiscing elit</p>
          </Padded>
        ),
      },
    ],
    default: "tab-code",
  },
  argTypes: {
    actionButtonPosition: {
      control: "radio",
      options: ["start", "end"],
    },
  },
  parameters: {
    controls: {
      exclude: ["tabs", "actionButton", "default", "actionButtonPosition"],
    },
  },
  render: ({ ...args }) => <TabSwitcherWrapper {...args} />,
};

export const Default = {};

const ActionButton = (
  <button
    onClick={() => {
      alert("You have clicked on the action button");
    }}
    style={{
      backgroundColor: "var(--tabs-bg-color)",
      height: "32px",
      border: "none",
      borderRadius: "8px",
      color: "var(--color-foreground-secondary)",
      display: "grid",
      placeItems: "center",
      appearance: "none",
    }}
  >
    A
  </button>
);

export const WithActionButton = {
  args: {
    actionButtonPosition: "start",
    actionButton: ActionButton,
  },
  parameters: {
    controls: {
      exclude: ["tabs", "actionButton", "defaultSelected"],
    },
  },
};

export const WithIcons = {
  args: {
    tabs: [
      {
        "aria-label": "Code",
        id: "tab-code",
        icon: "fill-content",
        content: <p>Lorem Ipsum</p>,
      },
      {
        "aria-label": "Design",
        id: "tab-design",
        icon: "pentool",
        content: <p>Dolor sit amet</p>,
      },
      {
        "aria-label": "Menu",
        id: "tab-menu",
        icon: "mask",
        content: <p>Consectetur adipiscing elit</p>,
      },
    ],
  },
};

export const WithIconsAndText = {
  args: {
    tabs: [
      {
        label: "Code",
        id: "tab-code",
        icon: "fill-content",
        content: <p>Lorem Ipsum</p>,
      },
      {
        label: "Design",
        id: "tab-design",
        icon: "pentool",
        content: <p>Dolor sit amet</p>,
      },
      {
        label: "Menu",
        id: "tab-menu",
        icon: "mask",
        content: <p>Consectetur adipiscing elit</p>,
      },
    ],
  },
};

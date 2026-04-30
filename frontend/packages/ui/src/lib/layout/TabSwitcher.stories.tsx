// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { TabSwitcher } from "./TabSwitcher";
import type { TabItem, TabSwitcherProps } from "./TabSwitcher";

interface TabWithContent extends TabItem {
  content: React.ReactNode;
}

function StatefulTabSwitcher({
  tabs,
  defaultSelected,
  ...props
}: Omit<TabSwitcherProps, "selected" | "onTabChange" | "tabs"> & {
  tabs: TabWithContent[];
  defaultSelected?: string;
}) {
  const [selected, setSelected] = useState(
    defaultSelected ?? tabs[0]?.id ?? "",
  );
  const navTabs: TabItem[] = tabs.map(({ content: _c, ...item }) => item);
  const contentMap = Object.fromEntries(tabs.map((t) => [t.id, t.content]));

  return (
    <TabSwitcher
      {...props}
      tabs={navTabs}
      selected={selected}
      onTabChange={setSelected}
    >
      {contentMap[selected]}
    </TabSwitcher>
  );
}

// ---------------------------------------------------------------------------
// Story data
// ---------------------------------------------------------------------------

const defaultTabs: TabWithContent[] = [
  { id: "tab-code", label: "Code", content: <p>Lorem Ipsum</p> },
  { id: "tab-design", label: "Design", content: <p>Dolor sit amet</p> },
  {
    id: "tab-menu",
    label: "Menu",
    content: <p>Consectetur adipiscing elit</p>,
  },
];

const iconTabs: TabWithContent[] = [
  {
    id: "tab-code",
    "aria-label": "Code",
    icon: "fill-content",
    content: <p>Lorem Ipsum</p>,
  },
  {
    id: "tab-design",
    "aria-label": "Design",
    icon: "pentool",
    content: <p>Dolor sit amet</p>,
  },
  {
    id: "tab-menu",
    "aria-label": "Menu",
    icon: "mask",
    content: <p>Consectetur adipiscing elit</p>,
  },
];

const iconAndTextTabs: TabWithContent[] = [
  {
    id: "tab-code",
    label: "Code",
    icon: "fill-content",
    content: <p>Lorem Ipsum</p>,
  },
  {
    id: "tab-design",
    label: "Design",
    icon: "pentool",
    content: <p>Dolor sit amet</p>,
  },
  {
    id: "tab-menu",
    label: "Menu",
    icon: "mask",
    content: <p>Consectetur adipiscing elit</p>,
  },
];

// ---------------------------------------------------------------------------
// Meta
// ---------------------------------------------------------------------------

const meta = {
  title: "Layout/Tab Switcher",
  component: StatefulTabSwitcher,
  args: {
    tabs: defaultTabs,
    defaultSelected: "tab-code",
  },
} satisfies Meta<typeof StatefulTabSwitcher>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithIcons: Story = {
  args: {
    tabs: iconTabs,
  },
};

export const WithIconsAndText: Story = {
  args: {
    tabs: iconAndTextTabs,
  },
};

export const WithActionButtonStart: Story = {
  args: {
    actionButtonPosition: "start",
    actionButton: (
      <button style={{ height: "32px", border: "none", borderRadius: "8px" }}>
        A
      </button>
    ),
  },
};

export const WithActionButtonEnd: Story = {
  args: {
    actionButtonPosition: "end",
    actionButton: (
      <button style={{ height: "32px", border: "none", borderRadius: "8px" }}>
        A
      </button>
    ),
  },
};

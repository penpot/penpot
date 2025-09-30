// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Switcher } = Components;

export default {
  title: "Controls/Switcher",
  component: Switcher,
  argTypes: {
    checked: {
      control: { type: "boolean" },
      description: "Controlled checked state",
    },
    defaultChecked: {
      control: { type: "boolean" },
      description: "Default checked state for uncontrolled mode",
    },
    label: {
      control: { type: "text" },
      description: "Label text displayed next to the switcher",
    },
    disabled: {
      control: { type: "boolean" },
      description: "Whether the switcher is disabled",
    },
    size: {
      options: ["sm", "md", "lg"],
      control: { type: "select" },
      description: "Size variant of the switcher",
    },
    "aria-label": {
      control: { type: "text" },
      description: "Accessible label when no visible label is provided",
    },
    onChange: {
      action: "changed",
      description: "Callback fired when the switcher state changes",
    },
  },
  args: {
    disabled: false,
    size: "md",
    defaultChecked: false,
  },
  parameters: {
    controls: { exclude: ["id", "class", "dataTestid", "on-change"] },
  },
  render: ({ onChange, ...args }) => (
    <Switcher {...args} onChange={onChange} data-testid="switcher" label="Enable notifications" />
  ),
};

export const DefaultUncontrolled = {
  args: {
    label: "Enable notifications",
    defaultChecked: false,
  },
};

export const Controlled = {
  render: ({ onChange, ...args }) => {
    const [checked, setChecked] = React.useState(false);
    
    const handleChange = (newChecked, event) => {
      setChecked(newChecked);
      if (onChange) onChange(newChecked, event);
    };
    
    return (
      <Switcher
        {...args}
        checked={checked}
        onChange={handleChange}
        data-testid="controlled-switcher"
      />
    );
  },
  args: {
    label: "Controlled switcher",
  },
};

export const WithLongLabel = {
  args: {
    label: "This is a very long label that demonstrates how the switcher component handles text wrapping and layout when the label content is extensive",
    defaultChecked: true,
  },
  render: ({ ...args }) => (
    <div style={{ maxWidth: "300px" }}>
      <Switcher {...args} data-testid="long-label-switcher" />
    </div>
  ),
};

export const WithoutVisibleLabel = {
  args: {
    "aria-label": "Toggle dark mode",
    defaultChecked: false,
  },
  render: ({ ...args }) => (
    <Switcher {...args} data-testid="no-label-switcher" />
  ),
};

export const Interactive = {
  render: () => {
    const [notifications, setNotifications] = React.useState(true);
    const [darkMode, setDarkMode] = React.useState(false);
    const [autoSave, setAutoSave] = React.useState(true);
    
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
        <Switcher
          label="Enable notifications"
          checked={notifications}
          onChange={(checked) => setNotifications(checked)}
          data-testid="notifications-switcher"
        />
        <Switcher
          label="Dark mode"
          checked={darkMode}
          onChange={(checked) => setDarkMode(checked)}
          data-testid="dark-mode-switcher"
        />
        <Switcher
          label="Auto-save documents"
          checked={autoSave}
          onChange={(checked) => setAutoSave(checked)}
          disabled={!notifications}
          data-testid="auto-save-switcher"
        />
        
        <div style={{ 
          marginTop: "16px", 
          padding: "12px", 
          backgroundColor: "#f5f5f5",
          color: "#000",
          borderRadius: "4px",
          fontSize: "14px"
        }}>
          <strong>Current state:</strong>
          <ul style={{ margin: "8px 0 0 0", paddingLeft: "20px" }}>
            <li>Notifications: {notifications ? "On" : "Off"}</li>
            <li>Dark mode: {darkMode ? "On" : "Off"}</li>
            <li>Auto-save: {autoSave ? "On" : "Off"}</li>
          </ul>
        </div>
      </div>
    );
  },
};

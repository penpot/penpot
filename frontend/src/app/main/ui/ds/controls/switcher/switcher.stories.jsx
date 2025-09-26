// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Switcher } = Components;

export default {
  title: "DS/Controls/Switcher",
  component: Components.Switcher,
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
    label: "Enable notifications",
    disabled: false,
    size: "md",
    defaultChecked: false,
  },
  parameters: {
    controls: { exclude: ["id", "class", "dataTestid"] },
  },
  render: ({ onChange, ...args }) => (
    <Switcher {...args} onChange={onChange} data-testid="switcher" />
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

export const Sizes = {
  render: () => (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      <Switcher
        size="sm"
        label="Small switcher"
        defaultChecked={false}
        data-testid="small-switcher"
      />
      <Switcher
        size="md"
        label="Medium switcher"
        defaultChecked={true}
        data-testid="medium-switcher"
      />
      <Switcher
        size="lg"
        label="Large switcher"
        defaultChecked={false}
        data-testid="large-switcher"
      />
    </div>
  ),
};

export const Disabled = {
  render: () => (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      <Switcher
        label="Disabled (off)"
        disabled={true}
        defaultChecked={false}
        data-testid="disabled-off"
      />
      <Switcher
        label="Disabled (on)"
        disabled={true}
        defaultChecked={true}
        data-testid="disabled-on"
      />
    </div>
  ),
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

export const Accessibility = {
  render: () => (
    <div style={{ display: "flex", flexDirection: "column", gap: "16px" }}>
      <div>
        <p style={{ marginBottom: "8px", fontSize: "14px", color: "#666" }}>
          Try using Tab to focus and Space/Enter to toggle:
        </p>
        <Switcher
          label="Keyboard accessible switcher"
          defaultChecked={false}
          data-testid="keyboard-switcher"
        />
      </div>
      <div>
        <p style={{ marginBottom: "8px", fontSize: "14px", color: "#666" }}>
          Screen reader accessible (no visible label):
        </p>
        <Switcher
          aria-label="Toggle feature X"
          defaultChecked={true}
          data-testid="screen-reader-switcher"
        />
      </div>
    </div>
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

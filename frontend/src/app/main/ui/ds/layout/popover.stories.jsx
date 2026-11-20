// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS SUBSIDIARY SL

import * as React from "react";
import Components from "@target/components";

const { Popover, Button } = Components;

// The popover only behaves: the story places and draws the panel, as a
// caller does.
const panelStyle = {
  position: "absolute",
  insetBlockStart: "calc(100% + 4px)",
  insetInlineStart: 0,
  inlineSize: "240px",
  padding: "8px",
  borderRadius: "8px",
  border: "1px solid var(--color-background-quaternary)",
  backgroundColor: "var(--color-background-tertiary)",
  color: "var(--color-foreground-primary)",
};

const PopoverWrapper = ({ isOpen: initial = false }) => {
  const [open, setOpen] = React.useState(initial);
  const close = React.useCallback(() => setOpen(false), []);

  return (
    <div style={{ position: "relative", display: "inline-block" }}>
      <Button variant="secondary" onClick={() => setOpen((open) => !open)}>
        Open panel
      </Button>
      <Popover isOpen={open} onClose={close}>
        <div style={panelStyle}>
          <p>A click out of this panel, or Escape, closes it.</p>
          <Button variant="ghost">A click here keeps it open</Button>
        </div>
      </Popover>
    </div>
  );
};

export default {
  title: "Layout/Popover",
  component: PopoverWrapper,
  args: {
    isOpen: false,
  },
  parameters: {
    controls: { exclude: ["isOpen"] },
  },
  render: (args) => <PopoverWrapper {...args} />,
};

export const Default = {};

export const Open = {
  args: {
    isOpen: true,
  },
};

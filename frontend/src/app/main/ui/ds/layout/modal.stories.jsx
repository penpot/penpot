// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import * as React from "react";
import Components from "@target/components";

const { Modal, Button, IconButton } = Components;
const { icons } = Components.meta;

const iconList = Object.entries(icons)
  .map(([_, value]) => value)
  .sort();

const ModalWrapper = ({ children, ...props }) => {
  const [open, setOpen] = React.useState(props.isOpen ?? false);

  React.useEffect(() => {
    setOpen(props.isOpen ?? false);
  }, [props.isOpen]);

  return (
    <>
      <Button onClick={() => setOpen(true)}>Open Modal</Button>
      <Modal
        {...props}
        isOpen={open}
        onOpenChange={setOpen}
      >
        {children ?? (
          <div>
            <p>Modal content goes here.</p>
          </div>
        )}
      </Modal>
    </>
  );
};

const DefaultContent = (
  <div>
    <p>This is the default modal content.</p>
    <p>You can put any React content inside.</p>
  </div>
);

const FooterContent = (
  <div
    style={{
      marginTop: "1rem",
      display: "flex",
      gap: "0.5rem",
      justifyContent: "flex-end",
    }}
  >
    <Button variant="secondary">Cancel</Button>
    <Button variant="primary">Confirm</Button>
  </div>
);

export default {
  title: "Layout/Modal",
  component: ModalWrapper,
  args: {
    heading: <h2 style={{ margin: 0, fontSize: "0.875rem", fontWeight: 500, lineHeight: 1.3, color: "var(--color-foreground-primary)" }}>Dialog Title</h2>,
    isOpen: true,
    size: "medium",
    isDismissable: true,
    children: (
      <>
        {DefaultContent}
        {FooterContent}
      </>
    ),
  },
  argTypes: {
    size: {
      control: "select",
      options: ["small", "medium", "large"],
    },
    isDismissable: { control: "boolean" },
  },
  parameters: {
    controls: { exclude: ["isOpen", "onOpenChange", "children", "trigger", "heading", "closeButton"] },
  },
  render: ({ ...args }) => <ModalWrapper {...args} />,
};

export const Default = {};

export const WithoutTitle = {
  args: {
    heading: undefined,
    children: DefaultContent,
  },
};

export const Small = {
  args: {
    size: "small",
    heading: <h2 style={{ margin: 0, fontSize: "0.875rem", fontWeight: 500, lineHeight: 1.3, color: "var(--color-foreground-primary)" }}>Confirm</h2>,
    children: (
      <>
        <p>Are you sure you want to proceed?</p>
        {FooterContent}
      </>
    ),
  },
};

export const Large = {
  args: {
    size: "large",
    heading: <h2 style={{ margin: 0, fontSize: "0.875rem", fontWeight: 500, lineHeight: 1.3, color: "var(--color-foreground-primary)" }}>Settings</h2>,
    children: DefaultContent,
  },
};

export const NonDismissable = {
  args: {
    isDismissable: false,
    heading: <h2 style={{ margin: 0, fontSize: "0.875rem", fontWeight: 500, lineHeight: 1.3, color: "var(--color-foreground-primary)" }}>Important</h2>,
    children: (
      <>
        <p>
          This modal cannot be closed by clicking the backdrop or pressing
          Escape.
        </p>
        {FooterContent}
      </>
    ),
  },
};

const IconFooterContent = (
  <div
    style={{
      marginTop: "1rem",
      display: "flex",
      gap: "0.5rem",
      justifyContent: "flex-end",
      alignItems: "center",
    }}
  >
    <Button variant="secondary">Cancel</Button>
    <Button variant="primary">Confirm</Button>
    <IconButton icon="close" variant="ghost" aria-label="Close" />
  </div>
);

export const WithIconButton = {
  args: {
    heading: <h2 style={{ margin: 0, fontSize: "0.875rem", fontWeight: 500, lineHeight: 1.3, color: "var(--color-foreground-primary)" }}>With close button</h2>,
    children: (
      <>
        <p>Modal showing a DS IconButton inside the content area.</p>
        {IconFooterContent}
      </>
    ),
  },
};

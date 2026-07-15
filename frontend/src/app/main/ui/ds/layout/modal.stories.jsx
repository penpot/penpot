// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import * as React from "react";
import Components from "@target/components";

const { Modal, ModalHeader, ModalContent, ModalFooter, Button, IconButton } =
  Components;

const ModalWrapper = ({ children, ...props }) => {
  const [open, setOpen] = React.useState(props.isOpen ?? false);

  React.useEffect(() => {
    setOpen(props.isOpen ?? false);
  }, [props.isOpen]);

  return (
    <>
      <Button onClick={() => setOpen(true)}>Open Modal</Button>
      <Modal {...props} isOpen={open} onOpenChange={setOpen}>
        {children}
      </Modal>
    </>
  );
};

export default {
  title: "Layout/Modal",
  component: ModalWrapper,
  args: {
    isOpen: false,
    size: "medium",
    isDismissable: true,
    children: (
      <>
        <ModalHeader title="Dialog Title" />
        <ModalContent>
          <p>This is the default modal content.</p>
          <p>You can put any React content inside.</p>
        </ModalContent>
        <ModalFooter>
          <Button variant="secondary">Cancel</Button>
          <Button variant="primary">Confirm</Button>
        </ModalFooter>
      </>
    ),
  },
  argTypes: {
    size: {
      control: "select",
      options: ["small", "medium", "large", "xlarge"],
    },
    isDismissable: { control: "boolean" },
  },
  parameters: {
    controls: { exclude: ["isOpen", "onOpenChange", "children"] },
  },
  render: ({ ...args }) => <ModalWrapper {...args} />,
};

export const Default = {};

export const WithoutTitle = {
  args: {
    hideClose: true,
    children: (
      <ModalContent>
        <p>This modal has no header or footer.</p>
      </ModalContent>
    ),
  },
};

export const DestructiveFooter = {
  args: {
    children: (
      <>
        <ModalHeader title="Delete item" />
        <ModalContent>
          <p>
            Are you sure you want to delete this item? This action cannot be
            undone.
          </p>
        </ModalContent>
        <ModalFooter
          variant="split"
          start={<Button variant="destructive">Delete</Button>}
          end={
            <>
              <Button variant="secondary">Cancel</Button>
              <Button variant="primary">Accept</Button>
            </>
          }
        />
      </>
    ),
  },
};

export const ScrollableContent = {
  args: {
    children: (
      <>
        <ModalHeader title="Terms and Conditions" />
        <ModalContent>
          {Array.from({ length: 20 }, (_, i) => (
            <p key={i}>
              Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do
              eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut
              enim ad minim veniam, quis nostrud exercitation ullamco laboris
              nisi ut aliquip ex ea commodo consequat.
            </p>
          ))}
        </ModalContent>
        <ModalFooter>
          <Button variant="secondary">Cancel</Button>
          <Button variant="primary">Accept</Button>
        </ModalFooter>
      </>
    ),
  },
};

export const NonDismissable = {
  args: {
    isDismissable: false,
    children: (
      <>
        <ModalHeader title="Important" />
        <ModalContent>
          <p>
            This modal cannot be closed by clicking the backdrop or pressing
            Escape.
          </p>
        </ModalContent>
        <ModalFooter>
          <Button variant="secondary">Cancel</Button>
          <Button variant="primary">Confirm</Button>
        </ModalFooter>
      </>
    ),
  },
};

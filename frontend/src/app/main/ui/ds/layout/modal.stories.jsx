// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import * as React from "react";
import Components from "@target/components";

const {
  Modal,
  ModalHeader,
  ModalContent,
  ModalFooter,
  ModalCloseButton,
  Button,
  IconButton,
} = Components;

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
      options: ["small", "medium", "large"],
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
    children: (
      <ModalContent>
        <p>This modal has no header or footer.</p>
      </ModalContent>
    ),
  },
};

export const Small = {
  args: {
    size: "small",
    children: (
      <>
        <ModalHeader title="Confirm" />
        <ModalContent>
          <p>Are you sure you want to proceed?</p>
        </ModalContent>
        <ModalFooter>
          <Button variant="secondary">Cancel</Button>
          <Button variant="primary">Confirm</Button>
        </ModalFooter>
      </>
    ),
  },
};

export const Large = {
  args: {
    size: "large",
    children: (
      <>
        <ModalHeader title="Settings" />
        <ModalContent>
          <p>This is the default modal content.</p>
          <p>You can put any React content inside.</p>
        </ModalContent>
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

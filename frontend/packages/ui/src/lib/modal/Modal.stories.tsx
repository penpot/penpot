import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react';
import { Modal, ModalCloseButton } from './Modal';

const meta: Meta<typeof Modal> = {
  title: 'Modal',
  component: Modal,
  argTypes: {
    isOpen: { control: 'boolean' },
    size: {
      control: 'select',
      options: ['small', 'medium', 'large'],
    },
    isDismissable: { control: 'boolean' },
  },
  args: {
    isOpen: true,
    size: 'medium',
    isDismissable: true,
  },
};

export default meta;
type Story = StoryObj<typeof Modal>;

const h2style = { margin: 0, fontSize: '0.875rem', fontWeight: 500, lineHeight: 1.3, color: 'var(--color-foreground-primary)' };
const headerStyle = { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.75rem 1rem', flexShrink: 0 };
const contentStyle = { padding: '1rem', overflowBlock: 'auto' };
const footerStyle = { display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '0.5rem', padding: '0.75rem 1rem', flexShrink: 0 };

export const Default: Story = {
  render: (args) => {
    const [open, setOpen] = useState(args.isOpen ?? false);
    return (
      <Modal {...args} isOpen={open} onOpenChange={setOpen}>
        <div style={headerStyle}>
          <h2 style={h2style}>Dialog Title</h2>
          <ModalCloseButton />
        </div>
        <div style={contentStyle}>
          <p>Default modal content.</p>
        </div>
        <div style={footerStyle}>
          <button>Cancel</button>
          <button>Confirm</button>
        </div>
      </Modal>
    );
  },
};

export const WithoutTitle: Story = {
  render: (args) => {
    const [open, setOpen] = useState(args.isOpen ?? false);
    return (
      <Modal {...args} isOpen={open} onOpenChange={setOpen}>
        <div style={contentStyle}>
          <p>No header or footer.</p>
        </div>
      </Modal>
    );
  },
};

export const Small: Story = {
  render: (args) => {
    const [open, setOpen] = useState(args.isOpen ?? false);
    return (
      <Modal {...args} isOpen={open} onOpenChange={setOpen} size="small">
        <div style={headerStyle}>
          <h2 style={h2style}>Confirm</h2>
          <ModalCloseButton />
        </div>
        <div style={contentStyle}>
          <p>Are you sure?</p>
        </div>
        <div style={footerStyle}>
          <button>Cancel</button>
          <button>Confirm</button>
        </div>
      </Modal>
    );
  },
};

export const Large: Story = {
  render: (args) => {
    const [open, setOpen] = useState(args.isOpen ?? false);
    return (
      <Modal {...args} isOpen={open} onOpenChange={setOpen} size="large">
        <div style={headerStyle}>
          <h2 style={h2style}>Settings</h2>
          <ModalCloseButton />
        </div>
        <div style={contentStyle}>
          <p>Large modal.</p>
        </div>
      </Modal>
    );
  },
};

export const NonDismissable: Story = {
  render: (args) => {
    const [open, setOpen] = useState(args.isOpen ?? false);
    return (
      <Modal {...args} isOpen={open} onOpenChange={setOpen} isDismissable={false}>
        <div style={headerStyle}>
          <h2 style={h2style}>Important</h2>
          <ModalCloseButton />
        </div>
        <div style={contentStyle}>
          <p>Cannot dismiss by clicking backdrop.</p>
        </div>
        <div style={footerStyle}>
          <ModalCloseButton>Close</ModalCloseButton>
          <button>Save</button>
        </div>
      </Modal>
    );
  },
};

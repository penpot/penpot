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

function ModalWrapper({ children, header, content, footer, ...args }: any) {
  const [open, setOpen] = useState(args.isOpen ?? false);
  return (
    <Modal {...args} isOpen={open} onOpenChange={setOpen} header={header} content={content} footer={footer}>
      {children}
    </Modal>
  );
}

export const Default: Story = {
  render: (args) => (
    <ModalWrapper
      {...args}
      header={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.75rem 1rem', flexShrink: 0 }}>
          <h2 style={h2style}>Dialog Title</h2>
          <ModalCloseButton />
        </div>
      }
      content={<div style={{ padding: '1rem', overflowBlock: 'auto' }}><p>Default modal content.</p></div>}
      footer={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: '0.5rem', padding: '0.75rem 1rem', flexShrink: 0 }}>
          <button>Cancel</button>
          <button>Confirm</button>
        </div>
      }
    />
  ),
};

export const WithTrigger: Story = {
  render: () => (
    <ModalWrapper
      isOpen={false}
      content={<div style={{ padding: '1rem' }}><p>Opened by trigger button.</p></div>}
    />
  ),
};

export const Small: Story = {
  render: (args) => (
    <ModalWrapper
      {...args}
      size="small"
      header={<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.75rem 1rem', flexShrink: 0 }}><h2 style={h2style}>Confirm</h2><ModalCloseButton /></div>}
      content={<div style={{ padding: '1rem' }}><p>Are you sure?</p></div>}
      footer={<div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end', padding: '0.75rem 1rem', flexShrink: 0 }}><button>Cancel</button><button>Confirm</button></div>}
    />
  ),
};

export const Large: Story = {
  render: (args) => (
    <ModalWrapper
      {...args}
      size="large"
      header={<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.75rem 1rem', flexShrink: 0 }}><h2 style={h2style}>Settings</h2><ModalCloseButton /></div>}
      content={<div style={{ padding: '1rem' }}><p>Large modal.</p></div>}
    />
  ),
};

export const WithoutTitle: Story = {
  render: (args) => (
    <ModalWrapper
      {...args}
      content={<div style={{ padding: '1rem' }}><p>No header or footer.</p></div>}
    />
  ),
};

export const NonDismissable: Story = {
  render: (args) => (
    <ModalWrapper
      {...args}
      isDismissable={false}
      header={<div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.75rem 1rem', flexShrink: 0 }}><h2 style={h2style}>Important</h2><ModalCloseButton /></div>}
      content={<div style={{ padding: '1rem' }}><p>Cannot dismiss by clicking backdrop.</p></div>}
      footer={<div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end', padding: '0.75rem 1rem', flexShrink: 0 }}><ModalCloseButton>Close</ModalCloseButton><button>Save</button></div>}
    />
  ),
};

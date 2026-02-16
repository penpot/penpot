import { Example } from './Example';
import type { Meta, StoryObj } from '@storybook/react-vite';

const meta = {
  title: 'UI/Example',
  component: Example,
} satisfies Meta<typeof Example>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Primary: Story = {};

/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import type { Meta, StoryObj } from "@storybook/react-vite";

import { Cta } from "./Cta";

const meta = {
  title: "Product/CTA",
  component: Cta,
  argTypes: {
    title: {
      control: { type: "text" },
    },
  },
  args: {
    title: "Autosaved versions will be kept for 7 days.",
  },
  render: ({ children, ...args }) => (
    <Cta {...args}>
      {children ?? (
        <span
          style={{
            fontSize: "0.75rem",
            color: "var(--color-foreground-secondary)",
          }}
        >
          If you&apos;d like to increase this limit, write to us at{" "}
          <a style={{ color: "var(--color-accent-primary)" }} href="#">
            support@penpot.app
          </a>
        </span>
      )}
    </Cta>
  ),
} satisfies Meta<typeof Cta>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

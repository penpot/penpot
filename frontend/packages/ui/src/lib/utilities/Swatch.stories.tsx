// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import type { Meta, StoryObj } from "@storybook/react-vite";
import { Swatch } from "./Swatch";

const meta = {
  title: "Foundations/Utilities/Swatch",
  component: Swatch,
  argTypes: {
    size: {
      control: "select",
      options: ["small", "medium", "large"],
    },
    active: { control: "boolean" },
    hasErrors: { control: "boolean" },
  },
  args: {
    background: { color: "#7efff5" },
    size: "medium",
    active: false,
    hasErrors: false,
  },
} satisfies Meta<typeof Swatch>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithOpacity: Story = {
  args: {
    background: { color: "#2f226c", opacity: 0.5 },
  },
};

const stops = [
  { color: "#151035", opacity: 1, offset: 0 },
  { color: "#2f226c", opacity: 0.5, offset: 1 },
];

export const LinearGradient: Story = {
  args: {
    background: {
      gradient: {
        type: "linear",
        stops,
      },
    },
  },
};

export const RadialGradient: Story = {
  args: {
    background: {
      gradient: {
        type: "radial",
        stops,
      },
    },
  },
};

export const Rounded: Story = {
  args: {
    background: {
      refId: "some-uuid",
      color: "#2f226c",
      opacity: 0.5,
    },
  },
};

export const Clickable: Story = {
  args: {
    onClick: (bg, _e) => {
      console.warn("clicked", bg);
    },
    "aria-label": "Click swatch",
  },
};

export const Large: Story = {
  args: {
    size: "large",
    background: { color: "#ff5733" },
  },
};

export const Error: Story = {
  args: {
    hasErrors: true,
  },
};

export const Active: Story = {
  args: {
    active: true,
    background: { color: "#4caf50" },
  },
};

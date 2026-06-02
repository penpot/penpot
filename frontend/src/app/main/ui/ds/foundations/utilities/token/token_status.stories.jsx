import * as React from "react";
import Components from "@target/components";

const { TokenStatusIcon } = Components;
const { tokenStatus } = Components.meta;

export default {
  title: "Foundations/Utilities/TokenStatus",
  component: TokenStatusIcon,
  argTypes: {
    iconId: {
      options: tokenStatus,
      control: { type: "select" },
    },
  },
  render: ({ ...args }) => <TokenStatusIcon {...args} />,
};

export const Default = {
  args: {
    iconId: "token-status-full",
  },
};

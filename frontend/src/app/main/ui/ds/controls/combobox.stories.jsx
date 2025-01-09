// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

import { userEvent, within, expect } from "@storybook/test";

const { Combobox } = Components;

let lastValue = null;

export default {
  title: "Controls/Combobox",
  component: Combobox,
  argTypes: {
    disabled: { control: "boolean" },
    hasError: { control: "boolean" },
  },
  args: {
    disabled: false,
    hasError: false,
    options: [
      { id: "January", label: "January" },
      { id: "February", label: "February" },
      { id: "March", label: "March" },
      { id: "April", label: "April" },
      { id: "May", label: "May" },
      { id: "June", label: "June" },
      { id: "July", label: "July" },
      { id: "August", label: "August" },
      { id: "September", label: "September" },
      { id: "October", label: "October" },
      { id: "November", label: "November" },
      { id: "December", label: "December" },
    ],
    defaultSelected: "February",
  },
  parameters: {
    controls: {
      exclude: ["options", "defaultSelected"],
    },
  },
  render: ({ ...args }) => (
    <div style={{ padding: "5px" }}>
      <Combobox {...args} />
    </div>
  ),
};

export const Default = {
  parameters: {
    docs: {
      story: {
        height: "450px",
      },
    },
  },
};

export const WithIcons = {
  args: {
    options: [
      { id: "January", label: "January", icon: "fill-content" },
      { id: "February", label: "February", icon: "pentool" },
      { id: "March", label: "March" },
      { id: "April", label: "April" },
      { id: "May", label: "May" },
      { id: "June", label: "June" },
      { id: "July", label: "July" },
      { id: "August", label: "August" },
      { id: "September", label: "September" },
      { id: "October", label: "October" },
      { id: "November", label: "November" },
      { id: "December", label: "December" },
    ],
  },
  parameters: {
    docs: {
      story: {
        height: "450px",
      },
    },
  },
};

export const TestInteractions = {
  ...WithIcons,
  args: {
    ...WithIcons.args,
    onChange: (value) => (lastValue = value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);

    const combobox = await canvas.getByRole("combobox");
    const button = await canvas.getByTestId("combobox-open-button");
    const input = await canvas.getByTestId("combobox-input");

    const waitOptionNotPresent = async () => {
      expect(canvas.queryByTestId("combobox-options")).not.toBeInTheDocument();
    };

    const waitOptionsPresent = async () => {
      const options = await canvas.findByTestId("combobox-options");
      expect(options).toBeVisible();

      return options;
    };

    await userEvent.clear(input);

    await step("Toggle dropdown on click arrow button", async () => {
      await userEvent.click(button);

      await waitOptionsPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "true");

      await userEvent.click(button);
      await waitOptionNotPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "false");
    });

    await step("Aria controls is set correctly", async () => {
      await userEvent.click(button);

      const ariaControls = combobox.getAttribute("aria-controls");

      const options = await canvas.findByTestId("combobox-options");

      expect(options).toHaveAttribute("id", ariaControls);
    });

    await step("Navigation keys", async () => {
      // Arrow down
      await userEvent.click(input);
      await waitOptionsPresent();

      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("February");
      expect(lastValue).toBe("February");
      await userEvent.clear(input);

      // Arrow up
      await userEvent.keyboard("{ArrowDown}");
      await waitOptionsPresent();

      await userEvent.keyboard("{ArrowUp}");
      await userEvent.keyboard("{ArrowUp}");
      expect(combobox).toHaveAttribute("aria-activedescendant", "November");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("November");
      expect(lastValue).toBe("November");
      await userEvent.clear(input);

      // Home
      await userEvent.keyboard("{ArrowDown}");
      await waitOptionsPresent();

      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{Home}");
      expect(combobox).toHaveAttribute("aria-activedescendant", "January");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("January");
      expect(lastValue).toBe("January");
      await userEvent.clear(input);
    });

    await step("Toggle dropdown with arrow down and ESC", async () => {
      userEvent.click(input);

      await waitOptionsPresent();

      await userEvent.keyboard("{Escape}");
      expect(combobox).toHaveAttribute("aria-expanded", "false");
      await waitOptionNotPresent();

      await userEvent.keyboard("{ArrowDown}");
      await waitOptionsPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "true");

      await userEvent.keyboard("{Escape}");
      await waitOptionNotPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "false");
    });

    await step("Filter with 'Ju' and select July", async () => {
      await userEvent.type(input, "Ju");

      const options = await canvas.findAllByTestId("dropdown-option");
      expect(options).toHaveLength(2);

      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{ArrowDown}");

      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("July");
      expect(lastValue).toBe("July");
    });

    await step("Close dropdown when focus out", async () => {
      await userEvent.click(button);

      await waitOptionsPresent();

      await userEvent.tab();

      await waitOptionNotPresent();
    });
  },
};

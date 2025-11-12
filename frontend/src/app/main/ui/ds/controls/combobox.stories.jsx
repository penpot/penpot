// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

import { userEvent, within, expect } from "storybook/test";

const { Combobox } = Components;

const options = [
  { id: "Monday", label: "Monday" },
  { id: "Tuesday", label: "Tuesday" },
  { id: "Wednesday", label: "Wednesday" },
  { id: "Thursday", label: "Thursday" },
  { id: "Friday", label: "Friday" },
  { id: "", label: "(Empty)" },
  { id: "Saturday", label: "Saturday" },
  { id: "Sunday", label: "Sunday" },
];

const optionsWithIcons = [
  { id: "Monday", label: "Monday", icon: "fill-content" },
  { id: "Tuesday", label: "Tuesday", icon: "pentool" },
  { id: "Wednesday", label: "Wednesday" },
  { id: "Thursday", label: "Thursday" },
  { id: "Friday", label: "Friday" },
  { id: "", label: "(Empty)" },
  { id: "Saturday", label: "Saturday" },
  { id: "Sunday", label: "Sunday" },
];

export default {
  title: "Controls/Combobox",
  component: Combobox,
  argTypes: {
    disabled: { control: "boolean" },
    maxLength: { control: "number" },
    hasError: { control: "boolean" },
    emptyToEnd: { control: "boolean" },
  },
  args: {
    disabled: false,
    maxLength: 10,
    hasError: false,
    placeholder: "Select a weekday",
    emptyToEnd: false,
    options: options,
    defaultSelected: "Tuesday",
  },
  parameters: {
    controls: {
      exclude: ["options", "defaultSelected"],
    },
    docs: {
      story: {
        height: "320px",
      },
    },
  },
  render: ({ ...args }) => <Combobox {...args} />,
};

export const Default = {};

export const WithIcons = {
  args: {
    options: optionsWithIcons,
  },
};

export const EmptyToEnd = {
  args: {
    emptyToEnd: true,
  },
};

let lastValue = null;

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

    await step("Toggle dropdown when clicking on arrow", async () => {
      await userEvent.clear(input);
      await userEvent.keyboard("{Escape}");

      await userEvent.click(button);

      await waitOptionsPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "true");

      await userEvent.click(button);
      await waitOptionNotPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "false");
    });

    await step("Open dropdown when clicking on input", async () => {
      await userEvent.clear(input);
      await userEvent.keyboard("{Escape}");

      await userEvent.click(input);

      await waitOptionsPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "true");

      await userEvent.keyboard("{Escape}");
      await waitOptionNotPresent();
      expect(combobox).toHaveAttribute("aria-expanded", "false");
    });

    await step("Aria controls set", async () => {
      await userEvent.clear(input);
      await userEvent.keyboard("{Escape}");

      await userEvent.click(button);

      const ariaControls = combobox.getAttribute("aria-controls");

      const options = await canvas.findByTestId("combobox-options");

      expect(options).toHaveAttribute("id", ariaControls);
    });

    await step("Navigation keys", async () => {
      await userEvent.clear(input);
      await userEvent.keyboard("{Escape}");

      // Arrow down
      await userEvent.click(input);
      await waitOptionsPresent();

      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("Tuesday");
      expect(lastValue).toBe("Tuesday");
      await userEvent.clear(input);

      // Arrow up
      await userEvent.keyboard("{ArrowDown}");
      await waitOptionsPresent();

      await userEvent.keyboard("{ArrowUp}");
      await userEvent.keyboard("{ArrowUp}");
      expect(combobox).toHaveAttribute("aria-activedescendant", "Saturday");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("Saturday");
      expect(lastValue).toBe("Saturday");
      await userEvent.clear(input);

      // Home
      await userEvent.keyboard("{ArrowDown}");
      await waitOptionsPresent();

      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{ArrowDown}");
      await userEvent.keyboard("{Home}");
      expect(combobox).toHaveAttribute("aria-activedescendant", "Monday");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("Monday");
      expect(lastValue).toBe("Monday");
      await userEvent.clear(input);
    });

    await step(
      "Filter with 'es' (Tuesday, Wednesday) and select Wednesday",
      async () => {
        await userEvent.clear(input);
        await userEvent.keyboard("{Escape}");

        await userEvent.click(input);

        await userEvent.type(input, "es");

        const options = await canvas.findAllByTestId("dropdown-option");
        expect(options).toHaveLength(2);

        await userEvent.keyboard("[ArrowDown]");
        await userEvent.keyboard("[ArrowDown]");

        await userEvent.keyboard("{Enter}");

        expect(input).toHaveValue("Wednesday");
        expect(lastValue).toBe("Wednesday");
      },
    );

    await step("Close dropdown when focusing out", async () => {
      await userEvent.clear(input);
      await userEvent.keyboard("{Escape}");

      await userEvent.click(button);

      await waitOptionsPresent();

      await userEvent.tab();

      await waitOptionNotPresent();
    });
  },
};

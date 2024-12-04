// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

/**
 * @type {import("@storybook/test")}
 */
import { userEvent, within, expect } from "@storybook/test";

const { Combobox } = Components;

export default {
  title: "Controls/Combobox",
  component: Combobox,
  argTypes: {
    disabled: { control: "boolean" },
  },
  args: {
    disabled: false,
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
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
 
    const combobox = await canvas.getByRole("combobox");
    const button = await canvas.getByTestId("combobox-open-button");
    const input = await canvas.getByTestId("combobox-input");

    const waitOptionNotPresent = async () => {
      expect(canvas.queryByTestId("combobox-options")).not.toBeInTheDocument();
    };

    const waitOptionsPresent = async () => {
      const options = await canvas.findByTestId("combobox-options")
      expect(options).toBeVisible();
    }

    await userEvent.clear(input);

    // await step("Toggle dropdown on click arrow button", async () => {
    //   await userEvent.click(button);

    //   const options = await getOptions();
    //   expect(options).toBeVisible();
    //   expect(combobox).toHaveAttribute("aria-expanded", "true");


    //   await userEvent.click(button);
    //   expect(options).not.toBeVisible();
    //   expect(combobox).toHaveAttribute("aria-expanded", "false");
    // });

    // await step("Toggle dropdown with arrow down and ESC", async () => {
    //   userEvent.click(input);

    //   await waitOptionsPresent();

    //   await userEvent.keyboard("{Escape}");
    //   expect(combobox).toHaveAttribute("aria-expanded", "false");
    //   await waitOptionNotPresent();

    //   await userEvent.keyboard("{ArrowDown}");
    //   await waitOptionsPresent();
    //   expect(combobox).toHaveAttribute("aria-expanded", "true");

    //   await userEvent.keyboard("{Escape}");
    //   await waitOptionNotPresent();
    //   expect(combobox).toHaveAttribute("aria-expanded", "false");
    // });

    // await step("Filter with 'Ju' and select July", async () => {
    //   await userEvent.type(input, "Ju");

    //   const options = await getOptions();
    //   expect(options).toHaveLength(2);

    //   await userEvent.keyboard("{ArrowDown}");
    //   await userEvent.keyboard("{ArrowDown}");

    //   await userEvent.keyboard("{Enter}");

    //   expect(input).toHaveValue("July");
    // });

    await step("Close dropdown when focus out", async () => {
      await userEvent.click(button);

      await waitOptionsPresent();

      await userEvent.tab();
      
      await waitOptionNotPresent();
    });
  },
};

export const Default = {
  parameters: {
    docs: {
      story: {
        height: "450px",
      },
    },
  }
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
  }
};

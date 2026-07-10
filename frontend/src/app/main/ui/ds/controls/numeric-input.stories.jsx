// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL
import * as React from "react";
import Components from "@target/components";
import { userEvent, within, expect } from "storybook/test";

const { NumericInput } = Components;
const { icons } = Components.meta;

export default {
  title: "Controls/Numeric Input",
  component: Components.NumericInput,
  argTypes: {
    placeholder: {
      control: { type: "text" },
    },
    disabled: {
      control: { type: "boolean" },
    },
    nillable: {
      control: { type: "boolean" },
    },
    min: {
      control: { type: "number" },
    },
    max: {
      control: { type: "number" },
    },
    step: {
      control: { type: "number" },
    },
    icon: {
      options: icons,
      control: { type: "select" },
    },
  },
  args: {
    placeholder: "--",
    disabled: false,
    nillable: false,
    icon: "search",
    property: "search",
  },
  parameters: {
    controls: { exclude: ["tokens"] },
  },

  render: ({ ...args }) => <NumericInput {...args} />,
};

export const Default = {};

export const WithTokens = {
  args: {
    placeholder: "--",
    disabled: false,
    nillable: false,
    icon: "search",
    min: 0,
    max: 100,
    step: 1,
    tokens: {
      dimensions: [
        {
          id: "ef79ae43-3f3f-8008-8006-988189c5e56e",
          name: "dimension-1",
          type: "dimensions",
          value: "30",
          description: "",
          "modified-at": "2025-08-04T12:02:00.087-00:00",
          resolvedValue: 30,
          unit: null,
        },
        {
          id: "ef79ae43-3f3f-8008-8006-98a5d53d85d0",
          name: "dimension-2",
          type: "dimensions",
          value: "20",
          description: "",
          "modified-at": "2025-08-04T14:40:34.550-00:00",
          resolvedValue: 20,
          unit: null,
        },
        {
          id: "ef79ae43-3f3f-8008-8006-98a5dd078629",
          name: "dimension-3",
          type: "dimensions",
          value: "100",
          description: "",
          "modified-at": "2025-08-04T14:40:42.526-00:00",
          resolvedValue: 100,
          unit: null,
        },
        {
          id: "ef79ae43-3f3f-8008-8006-98a5e75c0299",
          name: "dimension-4",
          type: "dimensions",
          value: "500",
          description: "",
          "modified-at": "2025-08-04T14:40:53.104-00:00",
          resolvedValue: 500,
          unit: null,
        },
      ],
      spacing: [
        {
          id: "ef79ae43-3f3f-8008-8006-98a5f1c64d5a",
          name: "spacing-1",
          type: "spacing",
          value: "32",
          description: "",
          "modified-at": "2025-08-04T14:41:03.769-00:00",
          resolvedValue: 32,
          unit: "px",
        },
      ],
    },
  },
  parameters: {
    controls: { exclude: ["tokens"] },
    docs: {
      story: {
        height: "320px",
      },
    },
  },
  render: ({ ...args }) => <NumericInput {...args} />,
};

// Regression tests for https://github.com/penpot/penpot/issues/10638 —
// on-change must never receive a string; only numbers, nil, or token ops.

const invalidInputCalls = [];

export const TestInvalidInputNeverEmitsString = {
  args: {
    // Mirrors the expanded padding fields on a mixed multi-selection:
    // no value, not nillable (layout_container.cljs multiple-padding-selection*).
    nillable: false,
    min: 0,
    icon: undefined,
    property: "padding",
    onChange: (value) => invalidInputCalls.push(value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    const input = await canvas.getByRole("textbox");

    await step("Invalid text commits no string values", async () => {
      invalidInputCalls.length = 0;

      await userEvent.click(input);
      await userEvent.type(input, "abc");
      await userEvent.keyboard("{Enter}");

      const nonNumeric = invalidInputCalls.filter(
        (v) => typeof v !== "number" && v !== null && v !== undefined,
      );
      expect(nonNumeric).toEqual([]);
    });

    await step("Whitespace-only input commits nothing", async () => {
      invalidInputCalls.length = 0;

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.type(input, "   ");
      await userEvent.keyboard("{Enter}");

      expect(invalidInputCalls).toEqual([]);
    });
  },
};

const precisionCalls = [];

export const TestPrecisionRevertEmitsNothing = {
  args: {
    // Full-precision committed value (e.g. from a 10/3 expression): the
    // display shows the rounded "3.33". Invalid input must revert the
    // display without emitting the rounded value — this exact divergence
    // leaked the rounded STRING before the fix (issue #10638).
    value: 3.3333333333333335,
    nillable: false,
    min: 0,
    icon: undefined,
    property: "padding",
    onChange: (value) => precisionCalls.push(value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    const input = await canvas.getByRole("textbox");

    await step("Invalid text reverts the display, emits nothing", async () => {
      precisionCalls.length = 0;

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.type(input, "abc");
      await userEvent.keyboard("{Enter}");

      expect(input).toHaveValue("3.33");
      expect(precisionCalls).toEqual([]);
    });
  },
};

const recommitCalls = [];

export const TestRecommitSameValueEmitsOnce = {
  args: {
    value: 33,
    nillable: false,
    min: 0,
    icon: undefined,
    property: "padding",
    onChange: (value) => recommitCalls.push(value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    const input = await canvas.getByRole("textbox");

    await step("Committing the same value twice emits once", async () => {
      recommitCalls.length = 0;

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.type(input, "50");
      await userEvent.keyboard("{Enter}");

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.type(input, "50");
      await userEvent.keyboard("{Enter}");

      expect(recommitCalls).toEqual([50]);
    });
  },
};

const nillableClearCalls = [];

export const TestNillableClearEmitsNil = {
  args: {
    value: 33,
    nillable: true,
    icon: undefined,
    property: "gap",
    onChange: (value) => nillableClearCalls.push(value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    const input = await canvas.getByRole("textbox");

    await step("Clearing a nillable input emits null", async () => {
      nillableClearCalls.length = 0;

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.keyboard("{Enter}");

      expect(nillableClearCalls).toEqual([null]);
      expect(input).toHaveValue("");
    });
  },
};

const unifyCalls = [];

export const TestTypingShownValueUnifiesMixed = {
  args: {
    // Same mixed-selection shape as above: the field displays "0" while the
    // shapes hold differing values, so committing "0" must still emit.
    nillable: false,
    min: 0,
    icon: undefined,
    property: "padding",
    onChange: (value) => unifyCalls.push(value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    const input = await canvas.getByRole("textbox");

    await step("Committing the displayed default emits a number", async () => {
      unifyCalls.length = 0;

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.type(input, "0");
      await userEvent.keyboard("{Enter}");

      expect(unifyCalls).toEqual([0]);
    });
  },
};

const escCalls = [];

export const TestEscRevertsWithoutCommit = {
  args: {
    value: 33,
    nillable: false,
    min: 0,
    icon: undefined,
    property: "padding",
    onChange: (value) => escCalls.push(value),
  },
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);
    const input = await canvas.getByRole("textbox");

    await step("Escape discards typed text and commits nothing", async () => {
      escCalls.length = 0;

      await userEvent.click(input);
      await userEvent.clear(input);
      await userEvent.type(input, "50");
      await userEvent.keyboard("{Escape}");

      expect(input).toHaveValue("33");
      expect(escCalls).toEqual([]);
    });
  },
};

const tokenCalls = [];

export const TestTokenApplyAlwaysEmits = {
  args: {
    ...WithTokens.args,
    // Token resolved value equals the current committed value on purpose:
    // applying it must still emit the token ops.
    value: 30,
    icon: undefined,
    property: "dimension",
    onChange: (value) => tokenCalls.push(value),
  },
  parameters: WithTokens.parameters,
  play: async ({ canvasElement, step }) => {
    const canvas = within(canvasElement);

    await step(
      "Applying a token with the same value emits token ops",
      async () => {
        tokenCalls.length = 0;

        const button = await canvas.getByRole("button");
        await userEvent.click(button);

        const option = await canvas.findByRole("option", {
          name: /dimension-1/,
        });
        await userEvent.click(option);

        expect(tokenCalls.length).toBe(1);
        // Token ops arrive as a CLJS vector (not a JS array): a non-null
        // object, never a scalar number/string.
        expect(typeof tokenCalls[0]).toBe("object");
        expect(tokenCalls[0]).not.toBeNull();
      },
    );

    await step(
      "Re-applying the same token emits nothing (toggle-token would unapply it)",
      async () => {
        const pill = await canvas.findByRole("button", {
          name: /dimension-1/,
        });
        await userEvent.click(pill);

        const option = await canvas.findByRole("option", {
          name: /dimension-1/,
        });
        await userEvent.click(option);

        expect(tokenCalls.length).toBe(1);
      },
    );
  },
};

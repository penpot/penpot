// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";

const { Tooltip } = Components;

export default {
  title: "Tooltip",
  component: Tooltip,
  argTypes: {
    placement: {
      description: "Position of the tooltip",
      control: { type: "select" },
      options: [
        "top",
        "bottom",
        "left",
        "right",
        "top-left",
        "top-right",
        "bottom-left",
        "bottom-right",
      ],
    },
    delay: {
      control: { type: "number" },
      description: "Delay in milliseconds before showing the tooltip",
    },
  },
  parameters: {
    controls: { exclude: ["children", "id"] },
  },
  args: {
    children: (
      <button popovertarget="popover-example">Hover this element</button>
    ),
    id: "popover-example",
    tooltipContent: "This is the tooltip content",
    delay: 300,
  },
  render: ({ children, ...args }) => (
    <div
      style={{
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        width: "100%",
        height: "100%",
      }}
    >
      <Tooltip {...args}>{children}</Tooltip>
    </div>
  ),
};

export const Default = {};

export const Corners = {
  render: ({}) => (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "repeat(3, 1fr)",
        gridTemplateRows: "repeat(3, 1fr)",
        gap: "1rem",
        width: "100%",
        height: "100%",
      }}
    >
      <Tooltip
        id="popover-example10"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          placeSelf: "start start",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example10">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example2"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "start",
          justifySelf: "center",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button
          popovertarget="popover-example2"
          style={{
            alignSelf: "start",
            justifySelf: "center",
            width: "fit-content",
          }}
        >
          Hover here
        </button>
      </Tooltip>
      <Tooltip
        id="popover-example3"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "start",
          justifySelf: "end",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example3">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example4"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "center",
          justifySelf: "start",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example4">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example5"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "center",
          justifySelf: "center",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example5">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example6"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "center",
          justifySelf: "end",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example6">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example7"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "end",
          justifySelf: "start",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example7">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example8"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "end",
          justifySelf: "center",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example8">Hover here</button>
      </Tooltip>
      <Tooltip
        id="popover-example9"
        tooltipContent="This is the tooltip content, and must be shown in two lines."
        style={{
          alignSelf: "end",
          justifySelf: "end",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example9">Hover here</button>
      </Tooltip>
    </div>
  ),
};

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
      control: { type: "select" },
    },
  },
  parameters: {
    controls: { exclude: ["children", "id"] },
  },
  args: {
    children: <button popovertarget="popover-example">haz hover aquí</button>,
    id: "popover-example",
    placement: "top",
    content: "Este es content del Tooltip que deberia ocupar más de una línea",
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

export const Default = {
  args: {
    placement: "bottom",
  },
};

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
        id="popover-example"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          placeSelf: "start start",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example">haz hover aquí</button>
      </Tooltip>
      <Tooltip
        id="popover-example2"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
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
          haz hover aquí
        </button>
      </Tooltip>
      <Tooltip
        id="popover-example3"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "start",
          justifySelf: "end",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button
          popovertarget="popover-example3"
          style={{
            width: "fit-content",
          }}
        >
          haz hover aquí
        </button>
      </Tooltip>
      <Tooltip
        id="popover-example4"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "center",
          justifySelf: "start",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example4">haz hover aquí</button>
      </Tooltip>
      <Tooltip
        id="popover-example5"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "center",
          justifySelf: "center",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example5">haz hover aquí</button>
      </Tooltip>
      <Tooltip
        id="popover-example6"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "center",
          justifySelf: "end",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example6">haz hover aquí</button>
      </Tooltip>
      <Tooltip
        id="popover-example7"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "end",
          justifySelf: "start",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example7">haz hover aquí</button>
      </Tooltip>
      <Tooltip
        id="popover-example8"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "end",
          justifySelf: "center",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example8">haz hover aquí</button>
      </Tooltip>
      <Tooltip
        id="popover-example9"
        content="Este es content del Tooltip que deberia ocupar más de una línea"
        style={{
          alignSelf: "end",
          justifySelf: "end",
          width: "fit-content",
          height: "fit-content",
        }}
      >
        <button popovertarget="popover-example9">haz hover aquí</button>
      </Tooltip>
    </div>
  ),
};

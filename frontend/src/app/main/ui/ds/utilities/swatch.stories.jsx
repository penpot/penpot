// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import * as React from "react";
import Components from "@target/components";
import { action } from "@storybook/addon-actions";

const { Swatch } = Components;

export default {
  title: "Foundations/Utilities/Swatch",
  component: Swatch,
  argTypes: {
    background: {
      control: "object",
    },
    size: {
      control: "select",
      options: ["small", "medium"],
    },
    active: {
      control: { type: "boolean" },
    },
  },
  args: {
    background: { color: "#7efff5" },
    size: "medium",
    active: false,
  },
  render: ({ ...args }) => <Swatch {...args} />,
};

export const Default = {};

export const WithOpacity = {
  args: {
    background: {
      color: "#7efff5",
      opacity: 0.5,
    },
  },
};

// These stories are disabled because the gradient and the UUID variants cannot be translated from cljs into JS
// When the repo is updated to use the new version of rumext, these stories should be re-enabled and tested
//
// export const LinearGradient = {
//   args: {
//     background: {
//       gradient: {
//         type: "linear",
//         startX: 0,
//         startY: 0,
//         endX: 1,
//         endY: 0,
//         width: 1,
//         stops: [
//           {
//             color: "#fabada",
//             opacity: 1,
//             offset: 0,
//           },
//           {
//             color: "#cc0000",
//             opacity: 0.5,
//             offset: 1,
//           },
//         ],
//       },
//     },
//   },
// };

// export const Rounded = {
//   args: {
//     background: {
//       id: crypto.randomUUID(),
//       color: "#7efff5",
//       opacity: 0.5,
//     },
//   },
// };

export const Small = {
  args: {
    size: "small",
  },
};

export const Active = {
  args: {
    active: true,
  },
};

export const Clickable = {
  args: {
    onClick: action("on-click"),
    "aria-label": "Click swatch",
  },
};

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

import * as React from "react";
import Components from "@target/components";

const { ToolToolbar } = Components;

const StoryLayout = ({ children }) => {
  return (
    <div
      style={{
        position: "relative",
        minHeight: "140px",
        width: "100%",
      }}
    >
      {children}
    </div>
  );
};

export default {
  title: "Toolbars/Tools",
  component: ToolToolbar,
  render: ({ ...args }) => (
    <StoryLayout>
      <ToolToolbar {...args} />
    </StoryLayout>
  ),
};

export const Default = {};

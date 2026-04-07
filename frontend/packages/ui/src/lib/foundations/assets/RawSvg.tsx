// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { type ComponentPropsWithRef, memo } from "react";

// ---------------------------------------------------------------------------
// Raw SVG asset ID catalogue
// ---------------------------------------------------------------------------

export const rawSvgIds = [
  "brand-openid",
  "brand-github",
  "brand-gitlab",
  "brand-google",
  "loader",
  "logo-error-screen",
  "logo-subscription",
  "logo-subscription-light",
  "nitrate-welcome",
  "marketing-arrows",
  "marketing-exchange",
  "marketing-file",
  "marketing-layers",
  "penpot-logo",
  "penpot-logo-icon",
  "empty-placeholder-1-left",
  "empty-placeholder-1-right",
  "empty-placeholder-2-left",
  "empty-placeholder-2-right",
] as const;

export type RawSvgId = (typeof rawSvgIds)[number];

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export interface RawSvgProps extends Omit<
  ComponentPropsWithRef<"svg">,
  "children"
> {
  /** Raw SVG asset identifier — must be one of the registered asset IDs. */
  id: RawSvgId;
}

function RawSvgInner({ id, ...rest }: RawSvgProps) {
  return (
    <svg {...rest}>
      <use href={`#asset-${id}`} />
    </svg>
  );
}

export const RawSvg = memo(RawSvgInner);

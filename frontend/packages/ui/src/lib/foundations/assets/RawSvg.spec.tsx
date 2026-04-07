// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { RawSvg, rawSvgIds } from "./RawSvg";

describe("RawSvg", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<RawSvg id="penpot-logo" />);
    expect(baseElement).toBeTruthy();
  });

  it("renders an <svg> element", () => {
    const { container } = render(<RawSvg id="penpot-logo" />);
    expect(container.querySelector("svg")).not.toBeNull();
  });

  it("renders a <use> element referencing the correct asset href", () => {
    const { container } = render(<RawSvg id="brand-github" />);
    const use = container.querySelector("use");
    expect(use?.getAttribute("href")).toBe("#asset-brand-github");
  });

  it("forwards width and height props to the svg element", () => {
    const { container } = render(
      <RawSvg id="penpot-logo" width={200} height={48} />,
    );
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("width")).toBe("200");
    expect(svg?.getAttribute("height")).toBe("48");
  });

  it("forwards className to the svg element", () => {
    const { container } = render(
      <RawSvg id="penpot-logo" className="my-svg" />,
    );
    expect(container.querySelector("svg")?.getAttribute("class")).toContain(
      "my-svg",
    );
  });

  it("forwards arbitrary svg props", () => {
    const { container } = render(
      <RawSvg id="penpot-logo" data-testid="raw-svg" aria-label="logo" />,
    );
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("data-testid")).toBe("raw-svg");
    expect(svg?.getAttribute("aria-label")).toBe("logo");
  });

  it("exports a non-empty rawSvgIds array", () => {
    expect(rawSvgIds.length).toBeGreaterThan(0);
  });

  it("includes expected asset IDs in rawSvgIds", () => {
    expect(rawSvgIds).toContain("penpot-logo");
    expect(rawSvgIds).toContain("brand-github");
    expect(rawSvgIds).toContain("loader");
  });
});

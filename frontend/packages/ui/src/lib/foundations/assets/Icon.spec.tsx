// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { Icon, iconIds } from "./Icon";

describe("Icon", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Icon iconId="pin" />);
    expect(baseElement).toBeTruthy();
  });

  it("renders an svg element", () => {
    const { container } = render(<Icon iconId="pin" />);
    expect(container.querySelector("svg")).not.toBeNull();
  });

  it("references the correct icon sprite href", () => {
    const { container } = render(<Icon iconId="add" />);
    const use = container.querySelector("use");
    expect(use?.getAttribute("href")).toBe("#icon-add");
  });

  it("defaults to medium size (16px viewport)", () => {
    const { container } = render(<Icon iconId="pin" />);
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("width")).toBe("16");
    expect(svg?.getAttribute("height")).toBe("16");
  });

  it("renders large size with 32px viewport", () => {
    const { container } = render(<Icon iconId="pin" size="l" />);
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("width")).toBe("32");
    expect(svg?.getAttribute("height")).toBe("32");
  });

  it("renders small size with 16px viewport and offset use", () => {
    const { container } = render(<Icon iconId="pin" size="s" />);
    const use = container.querySelector("use");
    expect(use?.getAttribute("width")).toBe("12");
    // offset = (16 - 12) / 2 = 2
    expect(use?.getAttribute("x")).toBe("2");
  });

  it("forwards className to the svg element", () => {
    const { container } = render(
      <Icon iconId="pin" className="custom-class" />,
    );
    expect(container.querySelector("svg")?.getAttribute("class")).toContain(
      "custom-class",
    );
  });

  it("forwards arbitrary svg props", () => {
    const { container } = render(
      <Icon iconId="pin" data-testid="my-icon" aria-label="pin icon" />,
    );
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("data-testid")).toBe("my-icon");
    expect(svg?.getAttribute("aria-label")).toBe("pin icon");
  });

  it("exports a non-empty iconIds array", () => {
    expect(iconIds.length).toBeGreaterThan(0);
  });
});

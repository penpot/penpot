// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render, screen } from "@testing-library/react";
import { IconButton } from "./IconButton";

describe("IconButton", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<IconButton icon="pin" aria-label="Pin" />);
    expect(baseElement).toBeTruthy();
  });

  it("renders a <button> element", () => {
    render(<IconButton icon="pin" aria-label="Pin" />);
    expect(screen.getByRole("button", { name: "Pin" })).toBeTruthy();
  });

  it("has type=button by default", () => {
    render(<IconButton icon="pin" aria-label="Pin" />);
    expect(screen.getByRole("button").getAttribute("type")).toBe("button");
  });

  it("renders an Icon inside", () => {
    const { container } = render(<IconButton icon="add" aria-label="Add" />);
    const use = container.querySelector("use");
    expect(use?.getAttribute("href")).toBe("#icon-add");
  });

  it("Icon is aria-hidden", () => {
    const { container } = render(<IconButton icon="pin" aria-label="Pin" />);
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("aria-hidden")).toBe("true");
  });

  it("applies primary variant class by default", () => {
    const { container } = render(<IconButton icon="pin" aria-label="Pin" />);
    expect(container.querySelector("button")?.className).toContain(
      "icon-button-primary",
    );
  });

  it("applies secondary variant class", () => {
    const { container } = render(
      <IconButton icon="pin" aria-label="Pin" variant="secondary" />,
    );
    expect(container.querySelector("button")?.className).toContain(
      "icon-button-secondary",
    );
  });

  it("applies ghost variant class", () => {
    const { container } = render(
      <IconButton icon="pin" aria-label="Pin" variant="ghost" />,
    );
    expect(container.querySelector("button")?.className).toContain(
      "icon-button-ghost",
    );
  });

  it("applies destructive variant class", () => {
    const { container } = render(
      <IconButton icon="pin" aria-label="Pin" variant="destructive" />,
    );
    expect(container.querySelector("button")?.className).toContain(
      "icon-button-destructive",
    );
  });

  it("applies action variant class", () => {
    const { container } = render(
      <IconButton icon="pin" aria-label="Pin" variant="action" />,
    );
    expect(container.querySelector("button")?.className).toContain(
      "icon-button-action",
    );
  });

  it("forwards iconClass to the Icon element", () => {
    const { container } = render(
      <IconButton icon="pin" aria-label="Pin" iconClass="my-icon" />,
    );
    expect(container.querySelector("svg")?.getAttribute("class")).toContain(
      "my-icon",
    );
  });

  it("merges custom className onto the button", () => {
    const { container } = render(
      <IconButton icon="pin" aria-label="Pin" className="custom" />,
    );
    expect(container.querySelector("button")?.className).toContain("custom");
  });

  it("calls onRef with the button DOM node", () => {
    const onRef = vi.fn();
    render(<IconButton icon="pin" aria-label="Pin" onRef={onRef} />);
    expect(onRef).toHaveBeenCalledWith(expect.any(HTMLButtonElement));
  });

  it("forwards arbitrary props to the button", () => {
    render(<IconButton icon="pin" aria-label="Pin" data-testid="ib" />);
    expect(screen.getByTestId("ib")).toBeTruthy();
  });

  it("supports disabled state", () => {
    render(<IconButton icon="pin" aria-label="Pin" disabled />);
    expect(screen.getByRole("button")).toHaveProperty("disabled", true);
  });
});

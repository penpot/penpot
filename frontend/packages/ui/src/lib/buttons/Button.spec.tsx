// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render, screen } from "@testing-library/react";
import { Button } from "./Button";

describe("Button", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Button>Click me</Button>);
    expect(baseElement).toBeTruthy();
  });

  it("renders as a <button> by default", () => {
    render(<Button>Click me</Button>);
    expect(screen.getByRole("button", { name: "Click me" })).toBeTruthy();
  });

  it("renders as an <a> when `to` is provided", () => {
    render(<Button to="https://example.com">Go</Button>);
    const link = screen.getByRole("link", { name: "Go" });
    expect(link.tagName).toBe("A");
    expect(link.getAttribute("href")).toBe("https://example.com");
  });

  it("applies primary variant class by default", () => {
    const { container } = render(<Button>Primary</Button>);
    const btn = container.querySelector("button");
    expect(btn?.className).toContain("button-primary");
  });

  it("applies secondary variant class", () => {
    const { container } = render(<Button variant="secondary">Sec</Button>);
    expect(container.querySelector("button")?.className).toContain(
      "button-secondary",
    );
  });

  it("applies ghost variant class", () => {
    const { container } = render(<Button variant="ghost">Ghost</Button>);
    expect(container.querySelector("button")?.className).toContain(
      "button-ghost",
    );
  });

  it("applies destructive variant class", () => {
    const { container } = render(<Button variant="destructive">Danger</Button>);
    expect(container.querySelector("button")?.className).toContain(
      "button-destructive",
    );
  });

  it("renders an Icon when `icon` is provided", () => {
    const { container } = render(<Button icon="pin">With icon</Button>);
    expect(container.querySelector("svg")).not.toBeNull();
  });

  it("does not render an Icon when `icon` is not provided", () => {
    const { container } = render(<Button>No icon</Button>);
    expect(container.querySelector("svg")).toBeNull();
  });

  it("merges custom className", () => {
    const { container } = render(<Button className="custom">Click me</Button>);
    expect(container.querySelector("button")?.className).toContain("custom");
  });

  it("calls onRef with the DOM node", () => {
    const onRef = vi.fn();
    render(<Button onRef={onRef}>Ref</Button>);
    expect(onRef).toHaveBeenCalledWith(expect.any(HTMLButtonElement));
  });

  it("forwards arbitrary props to the root element", () => {
    render(<Button data-testid="my-btn">Click</Button>);
    expect(screen.getByTestId("my-btn")).toBeTruthy();
  });
});

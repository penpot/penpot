// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { Label } from "./Label";

describe("Label", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Label htmlFor="my-input">Name</Label>);
    expect(baseElement).toBeTruthy();
  });

  it("should render a <label> element", () => {
    const { container } = render(<Label htmlFor="my-input">Name</Label>);
    const label = container.querySelector("label");
    expect(label).toBeTruthy();
  });

  it("should set htmlFor on the label", () => {
    const { container } = render(<Label htmlFor="my-input">Name</Label>);
    const label = container.querySelector("label");
    expect(label?.getAttribute("for")).toBe("my-input");
  });

  it("should render children inside label-text span", () => {
    const { container } = render(<Label htmlFor="x">My Field</Label>);
    const span = container.querySelector("label span");
    expect(span?.textContent).toBe("My Field");
  });

  it("should not render label-text span when children is undefined", () => {
    const { container } = render(<Label htmlFor="x" isOptional={true} />);
    // Only the optional span should be present
    const spans = container.querySelectorAll("label span");
    expect(spans.length).toBe(1);
    expect(spans[0]?.textContent).toBe("(Optional)");
  });

  it("should not render optional span by default", () => {
    const { container } = render(<Label htmlFor="x">Label</Label>);
    const spans = container.querySelectorAll("label span");
    expect(spans.length).toBe(1);
    // The single span is label-text, not optional
    expect(spans[0]?.textContent).toBe("Label");
  });

  it("should render optional span when isOptional is true", () => {
    const { container } = render(
      <Label htmlFor="x" isOptional={true}>
        Label
      </Label>,
    );
    const spans = container.querySelectorAll("label span");
    expect(spans.length).toBe(2);
    expect(spans[1]?.textContent).toBe("(Optional)");
  });

  it("should merge className", () => {
    const { container } = render(
      <Label htmlFor="x" className="custom">
        Label
      </Label>,
    );
    const label = container.querySelector("label");
    const cls = label?.getAttribute("class") ?? "";
    expect(cls).toContain("custom");
  });

  it("should pass through additional HTML attributes", () => {
    const { container } = render(
      <Label htmlFor="x" data-testid="my-label">
        Label
      </Label>,
    );
    const label = container.querySelector("label");
    expect(label?.getAttribute("data-testid")).toBe("my-label");
  });
});

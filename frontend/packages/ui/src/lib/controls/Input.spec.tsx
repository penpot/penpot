// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { Input } from "./Input";

describe("Input", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Input />);
    expect(baseElement).toBeTruthy();
  });

  it("should render an <input> element", () => {
    const { container } = render(<Input />);
    expect(container.querySelector("input")).toBeTruthy();
  });

  it("should render a label when label prop is provided", () => {
    const { container } = render(<Input label="Name" />);
    const label = container.querySelector("label");
    expect(label).toBeTruthy();
    expect(label?.textContent).toContain("Name");
  });

  it("should not render a label when label is not provided", () => {
    const { container } = render(<Input />);
    expect(container.querySelector("label")).toBeNull();
  });

  it("should associate label with input via id", () => {
    const { container } = render(<Input id="my-input" label="Name" />);
    const label = container.querySelector("label");
    const input = container.querySelector("input");
    expect(label?.getAttribute("for")).toBe("my-input");
    expect(input?.getAttribute("id")).toBe("my-input");
  });

  it("should auto-generate id when not provided", () => {
    const { container } = render(<Input label="Name" />);
    const label = container.querySelector("label");
    const input = container.querySelector("input");
    const forAttr = label?.getAttribute("for");
    expect(forAttr).toBeTruthy();
    expect(input?.getAttribute("id")).toBe(forAttr);
  });

  it("should render hint message when hintMessage is provided", () => {
    const { container } = render(<Input hintMessage="A hint" />);
    const span = container.querySelector("span");
    expect(span?.textContent).toBe("A hint");
  });

  it("should not render hint when hintMessage is not provided", () => {
    const { container } = render(<Input />);
    // The only children should be the InputField wrapper div
    expect(container.querySelector("span")).toBeNull();
  });

  it("should render Optional suffix when isOptional is true", () => {
    const { container } = render(<Input label="Name" isOptional />);
    expect(container.textContent).toContain("(Optional)");
  });

  it("should merge className on the outer wrapper", () => {
    const { container } = render(<Input className="extra" />);
    const wrapper = container.firstElementChild;
    expect(wrapper?.getAttribute("class")).toContain("extra");
  });

  it("should pass through additional HTML attributes to the input", () => {
    const { container } = render(<Input data-testid="my-input" />);
    const input = container.querySelector("input");
    expect(input?.getAttribute("data-testid")).toBe("my-input");
  });

  it("should pass type prop to the input", () => {
    const { container } = render(<Input type="email" />);
    const input = container.querySelector("input");
    expect(input?.getAttribute("type")).toBe("email");
  });
});

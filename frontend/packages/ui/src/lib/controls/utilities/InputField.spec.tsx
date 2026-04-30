// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { InputField } from "./InputField";

describe("InputField", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<InputField id="f" />);
    expect(baseElement).toBeTruthy();
  });

  it("should render an <input> element", () => {
    const { container } = render(<InputField id="f" />);
    expect(container.querySelector("input")).toBeTruthy();
  });

  it("should set id on the input", () => {
    const { container } = render(<InputField id="my-input" />);
    const input = container.querySelector("input");
    expect(input?.getAttribute("id")).toBe("my-input");
  });

  it("should render an icon when icon prop is provided", () => {
    const { container } = render(<InputField id="f" icon="search" />);
    expect(container.querySelector("svg")).toBeTruthy();
  });

  it("should not render an icon by default", () => {
    const { container } = render(<InputField id="f" />);
    expect(container.querySelector("svg")).toBeNull();
  });

  it("should set aria-invalid=true when hasHint and hintType=error", () => {
    const { container } = render(
      <InputField id="f" hasHint hintType="error" />,
    );
    const input = container.querySelector("input");
    expect(input?.getAttribute("aria-invalid")).toBe("true");
  });

  it("should not set aria-invalid when hasHint and hintType=warning", () => {
    const { container } = render(
      <InputField id="f" hasHint hintType="warning" />,
    );
    const input = container.querySelector("input");
    expect(input?.getAttribute("aria-invalid")).toBeNull();
  });

  it("should set aria-describedby to `${id}-hint` when hasHint", () => {
    const { container } = render(<InputField id="my-field" hasHint />);
    const input = container.querySelector("input");
    expect(input?.getAttribute("aria-describedby")).toBe("my-field-hint");
  });

  it("should render slotStart content", () => {
    const { container } = render(
      <InputField id="f" slotStart={<span data-testid="slot-start" />} />,
    );
    expect(container.querySelector("[data-testid='slot-start']")).toBeTruthy();
  });

  it("should render slotEnd content", () => {
    const { container } = render(
      <InputField id="f" slotEnd={<span data-testid="slot-end" />} />,
    );
    expect(container.querySelector("[data-testid='slot-end']")).toBeTruthy();
  });

  it("should merge className on the wrapper", () => {
    const { container } = render(<InputField id="f" className="extra" />);
    const wrapper = container.firstElementChild;
    expect(wrapper?.getAttribute("class")).toContain("extra");
  });

  it("should pass through additional HTML attributes to the input", () => {
    const { container } = render(<InputField id="f" data-testid="my-input" />);
    const input = container.querySelector("input");
    expect(input?.getAttribute("data-testid")).toBe("my-input");
  });
});

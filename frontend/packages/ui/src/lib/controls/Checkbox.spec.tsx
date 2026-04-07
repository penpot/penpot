// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { Checkbox } from "./Checkbox";

describe("Checkbox", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Checkbox id="cb" onChange={() => {}} />);
    expect(baseElement).toBeTruthy();
  });

  it("should render an <input type='checkbox'>", () => {
    const { container } = render(<Checkbox id="cb" onChange={() => {}} />);
    const input = container.querySelector("input[type='checkbox']");
    expect(input).toBeTruthy();
  });

  it("should associate label with input via id", () => {
    const { container } = render(
      <Checkbox id="cb" label="My field" onChange={() => {}} />,
    );
    const label = container.querySelector("label");
    expect(label?.getAttribute("for")).toBe("cb");
  });

  it("should render label text", () => {
    const { container } = render(
      <Checkbox id="cb" label="Accept" onChange={() => {}} />,
    );
    const text = container.querySelector("[class*='checkbox-text']");
    expect(text?.textContent).toBe("Accept");
  });

  it("should render tick icon when checked", () => {
    const { container } = render(
      <Checkbox id="cb" checked onChange={() => {}} />,
    );
    const svg = container.querySelector("svg");
    expect(svg).toBeTruthy();
  });

  it("should not render tick icon when unchecked", () => {
    const { container } = render(
      <Checkbox id="cb" checked={false} onChange={() => {}} />,
    );
    const svg = container.querySelector("svg");
    expect(svg).toBeNull();
  });

  it("should pass disabled to the input", () => {
    const { container } = render(
      <Checkbox id="cb" disabled onChange={() => {}} />,
    );
    const input = container.querySelector("input") as HTMLInputElement;
    expect(input.disabled).toBe(true);
  });

  it("should merge className on the wrapper div", () => {
    const { container } = render(
      <Checkbox id="cb" className="extra" onChange={() => {}} />,
    );
    const wrapper = container.firstElementChild;
    expect(wrapper?.getAttribute("class")).toContain("extra");
  });

  it("should pass through additional HTML attributes to the input", () => {
    const { container } = render(
      <Checkbox id="cb" data-testid="my-cb" onChange={() => {}} />,
    );
    const input = container.querySelector("input");
    expect(input?.getAttribute("data-testid")).toBe("my-cb");
  });
});

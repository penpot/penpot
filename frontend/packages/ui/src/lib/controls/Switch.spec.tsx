// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { fireEvent, render } from "@testing-library/react";
import { Switch } from "./Switch";

describe("Switch", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Switch />);
    expect(baseElement).toBeTruthy();
  });

  it("should render with role=switch", () => {
    const { container } = render(<Switch />);
    const el = container.querySelector("[role='switch']");
    expect(el).toBeTruthy();
  });

  it("should reflect aria-checked=false when defaultChecked is false", () => {
    const { container } = render(<Switch defaultChecked={false} />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("aria-checked")).toBe("false");
  });

  it("should reflect aria-checked=true when defaultChecked is true", () => {
    const { container } = render(<Switch defaultChecked={true} />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("aria-checked")).toBe("true");
  });

  it("should toggle from false to true on click", () => {
    const onChange = vi.fn();
    const { container } = render(
      <Switch defaultChecked={false} onChange={onChange} />,
    );
    const el = container.querySelector("[role='switch']") as HTMLElement;
    fireEvent.click(el);
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("should toggle from true to false on click", () => {
    const onChange = vi.fn();
    const { container } = render(
      <Switch defaultChecked={true} onChange={onChange} />,
    );
    const el = container.querySelector("[role='switch']") as HTMLElement;
    fireEvent.click(el);
    expect(onChange).toHaveBeenCalledWith(false);
  });

  it("should toggle on Space key", () => {
    const onChange = vi.fn();
    const { container } = render(
      <Switch defaultChecked={false} onChange={onChange} />,
    );
    const el = container.querySelector("[role='switch']") as HTMLElement;
    fireEvent.keyDown(el, { key: " " });
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("should toggle on Enter key", () => {
    const onChange = vi.fn();
    const { container } = render(
      <Switch defaultChecked={false} onChange={onChange} />,
    );
    const el = container.querySelector("[role='switch']") as HTMLElement;
    fireEvent.keyDown(el, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("should not toggle when disabled", () => {
    const onChange = vi.fn();
    const { container } = render(
      <Switch defaultChecked={false} disabled onChange={onChange} />,
    );
    const el = container.querySelector("[role='switch']") as HTMLElement;
    fireEvent.click(el);
    expect(onChange).not.toHaveBeenCalled();
  });

  it("should set tabIndex=-1 when disabled", () => {
    const { container } = render(<Switch disabled />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("tabindex")).toBe("-1");
  });

  it("should set aria-disabled when disabled", () => {
    const { container } = render(<Switch disabled />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("aria-disabled")).toBe("true");
  });

  it("should set tabIndex=0 when not disabled", () => {
    const { container } = render(<Switch />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("tabindex")).toBe("0");
  });

  it("should render label when provided", () => {
    const { container } = render(<Switch label="Toggle" />);
    const lbl = container.querySelector("label");
    expect(lbl?.textContent).toBe("Toggle");
  });

  it("should not render label element when label is not provided", () => {
    const { container } = render(<Switch />);
    expect(container.querySelector("label")).toBeNull();
  });

  it("should use aria-label when no visible label", () => {
    const { container } = render(<Switch aria-label="Toggle feature" />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("aria-label")).toBe("Toggle feature");
  });

  it("should not set aria-label on root when label prop is present", () => {
    const { container } = render(
      <Switch label="Toggle" aria-label="Toggle feature" />,
    );
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("aria-label")).toBeNull();
  });

  it("should merge className", () => {
    const { container } = render(<Switch className="extra" />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("class")).toContain("extra");
  });

  it("should pass through HTML attributes", () => {
    const { container } = render(<Switch data-testid="sw" />);
    const el = container.querySelector("[role='switch']");
    expect(el?.getAttribute("data-testid")).toBe("sw");
  });
});

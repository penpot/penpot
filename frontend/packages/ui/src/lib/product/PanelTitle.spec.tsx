// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PanelTitle } from "./PanelTitle";

describe("PanelTitle", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<PanelTitle text="My Panel" />);
    expect(baseElement).toBeTruthy();
  });

  it("should render as a div", () => {
    const { container } = render(<PanelTitle text="My Panel" />);
    expect(container.firstElementChild?.tagName.toLowerCase()).toBe("div");
  });

  it("should apply panel-title class to root element", () => {
    const { container } = render(<PanelTitle text="My Panel" />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "panel-title",
    );
  });

  it("should render the text prop in a span", () => {
    const { getByText } = render(<PanelTitle text="My Panel" />);
    const span = getByText("My Panel");
    expect(span.tagName.toLowerCase()).toBe("span");
  });

  it("should not render a close button when onClose is not provided", () => {
    const { container } = render(<PanelTitle text="My Panel" />);
    const button = container.querySelector("button");
    expect(button).toBeNull();
  });

  it("should render a close button when onClose is provided", () => {
    const { container } = render(
      <PanelTitle text="My Panel" onClose={() => {}} />,
    );
    const button = container.querySelector("button");
    expect(button).toBeTruthy();
  });

  it("should call onClose when close button is clicked", () => {
    const onClose = vi.fn();
    const { container } = render(
      <PanelTitle text="My Panel" onClose={onClose} />,
    );
    const button = container.querySelector("button") as HTMLButtonElement;
    button.click();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("should forward className to the root div", () => {
    const { container } = render(
      <PanelTitle text="My Panel" className="custom-cls" />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "custom-cls",
    );
  });

  it("should spread extra props onto the root div", () => {
    const { container } = render(
      <PanelTitle text="My Panel" data-testid="panel-header" />,
    );
    expect(container.firstElementChild?.getAttribute("data-testid")).toBe(
      "panel-header",
    );
  });
});

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<EmptyState icon="help" text="Empty" />);
    expect(baseElement).toBeTruthy();
  });

  it("should render the provided text", () => {
    const { getByText } = render(
      <EmptyState icon="help" text="Nothing to see here" />,
    );
    expect(getByText("Nothing to see here")).toBeTruthy();
  });

  it("should render an SVG icon", () => {
    const { container } = render(<EmptyState icon="help" text="Empty" />);
    const svg = container.querySelector("svg");
    expect(svg).toBeTruthy();
  });

  it("should pass className to wrapper", () => {
    const { container } = render(
      <EmptyState icon="help" text="Empty" className="my-class" />,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("class")).toContain("my-class");
  });

  it("should spread extra props onto wrapper div", () => {
    const { container } = render(
      <EmptyState icon="help" text="Empty" data-testid="empty-state" />,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("data-testid")).toBe("empty-state");
  });

  it("should apply the group class to the wrapper", () => {
    const { container } = render(<EmptyState icon="help" text="Empty" />);
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("class")).toContain("group");
  });

  it("should render the icon inside an icon-wrapper div", () => {
    const { container } = render(<EmptyState icon="help" text="Empty" />);
    const iconWrapper = container.querySelector("[class*='icon-wrapper']");
    expect(iconWrapper).toBeTruthy();
  });

  it("should render text inside a div with text class", () => {
    const { container } = render(
      <EmptyState icon="help" text="Some text here" />,
    );
    const textDiv = container.querySelector("[class*='text']");
    expect(textDiv?.textContent).toBe("Some text here");
  });
});

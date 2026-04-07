// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { EmptyPlaceholder } from "./EmptyPlaceholder";

describe("EmptyPlaceholder", () => {
  it("should render with required title", () => {
    const { baseElement } = render(<EmptyPlaceholder title="No items" />);
    expect(baseElement).toBeTruthy();
  });

  it("should set data-testid on root", () => {
    const { getByTestId } = render(<EmptyPlaceholder title="No items" />);
    expect(getByTestId("empty-placeholder")).toBeTruthy();
  });

  it("should render the title", () => {
    const { getByText } = render(<EmptyPlaceholder title="No items" />);
    expect(getByText("No items")).toBeTruthy();
  });

  it("should render subtitle when provided", () => {
    const { getByText } = render(
      <EmptyPlaceholder title="No items" subtitle="Try again later" />,
    );
    expect(getByText("Try again later")).toBeTruthy();
  });

  it("should not render subtitle when omitted", () => {
    const { queryByText } = render(<EmptyPlaceholder title="No items" />);
    expect(queryByText("Try again later")).toBeNull();
  });

  it("should render two svg decoration elements", () => {
    const { baseElement } = render(<EmptyPlaceholder title="No items" />);
    const svgs = baseElement.querySelectorAll("svg");
    expect(svgs.length).toBe(2);
  });

  it("should use type-1 svg ids by default", () => {
    const { baseElement } = render(<EmptyPlaceholder title="No items" />);
    const uses = baseElement.querySelectorAll("use");
    expect(uses[0].getAttribute("href")).toBe(
      "#asset-empty-placeholder-1-left",
    );
    expect(uses[1].getAttribute("href")).toBe(
      "#asset-empty-placeholder-1-right",
    );
  });

  it("should use type-2 svg ids when type is 2", () => {
    const { baseElement } = render(
      <EmptyPlaceholder title="No items" type={2} />,
    );
    const uses = baseElement.querySelectorAll("use");
    expect(uses[0].getAttribute("href")).toBe(
      "#asset-empty-placeholder-2-left",
    );
    expect(uses[1].getAttribute("href")).toBe(
      "#asset-empty-placeholder-2-right",
    );
  });

  it("should forward extra className", () => {
    const { getByTestId } = render(
      <EmptyPlaceholder title="No items" className="extra" />,
    );
    expect(getByTestId("empty-placeholder").getAttribute("class")).toContain(
      "extra",
    );
  });

  it("should forward extra HTML attributes", () => {
    const { getByTestId } = render(
      <EmptyPlaceholder title="No items" data-foo="bar" />,
    );
    expect(getByTestId("empty-placeholder").getAttribute("data-foo")).toBe(
      "bar",
    );
  });

  it("should render children inside text-wrapper", () => {
    const { getByText } = render(
      <EmptyPlaceholder title="No items">
        <span>child content</span>
      </EmptyPlaceholder>,
    );
    expect(getByText("child content")).toBeTruthy();
  });
});

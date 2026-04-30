// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Loader } from "./Loader";

describe("Loader", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Loader />);
    expect(baseElement).toBeTruthy();
  });

  it("should render the SVG loader icon with role='status'", () => {
    const { container } = render(<Loader />);
    const svg = container.querySelector("svg[role='status']");
    expect(svg).toBeTruthy();
  });

  it("should apply the wrapper class by default", () => {
    const { container } = render(<Loader />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "wrapper",
    );
  });

  it("should apply wrapper-overlay class when overlay=true", () => {
    const { container } = render(<Loader overlay />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "wrapper-overlay",
    );
  });

  it("should apply file-loading class when fileLoading=true", () => {
    const { container } = render(<Loader fileLoading />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "file-loading",
    );
  });

  it("should use default width and height of 100x27 when none given", () => {
    const { container } = render(<Loader />);
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("width")).toBe("100");
    expect(svg?.getAttribute("height")).toBe("27");
  });

  it("should calculate height from width when only width is given", () => {
    const { container } = render(<Loader width={200} />);
    const svg = container.querySelector("svg");
    expect(svg?.getAttribute("width")).toBe("200");
    // height = ceil(200 * 27/100) = 54
    expect(svg?.getAttribute("height")).toBe("54");
  });

  it("should calculate width from height when only height is given", () => {
    const { container } = render(<Loader height={54} />);
    const svg = container.querySelector("svg");
    // width = ceil(54 * 100/27) = 200
    expect(svg?.getAttribute("width")).toBe("200");
    expect(svg?.getAttribute("height")).toBe("54");
  });

  it("should use the provided title as the SVG <title>", () => {
    const { container } = render(<Loader title="Please wait" />);
    const title = container.querySelector("svg title");
    expect(title?.textContent).toBe("Please wait");
  });

  it("should render children", () => {
    const { getByText } = render(<Loader>Loading text</Loader>);
    expect(getByText("Loading text")).toBeTruthy();
  });

  it("should forward className to the root div", () => {
    const { container } = render(<Loader className="custom-loader" />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "custom-loader",
    );
  });

  it("should not show tips container initially without fileLoading", () => {
    const { container } = render(<Loader />);
    const tips = container.querySelector("[class*='tips-container']");
    expect(tips).toBeNull();
  });
});

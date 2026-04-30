/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { render } from "@testing-library/react";

import { Cta } from "./Cta";

describe("Cta", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Cta title="Hello" />);
    expect(baseElement).toBeTruthy();
  });

  it("should render the title text", () => {
    const { getByText } = render(<Cta title="Upgrade your plan" />);
    expect(getByText("Upgrade your plan")).toBeTruthy();
  });

  it("should render children in the message area", () => {
    const { getByText } = render(
      <Cta title="Notice">
        <span>Read more details here</span>
      </Cta>,
    );
    expect(getByText("Read more details here")).toBeTruthy();
  });

  it("should render with data-testid attribute", () => {
    const { container } = render(<Cta title="Test" />);
    const element = container.querySelector("[data-testid='cta']");
    expect(element).toBeTruthy();
  });

  it("should merge custom className", () => {
    const { container } = render(
      <Cta title="Styled" className="custom-class" />,
    );
    const element = container.querySelector("[data-testid='cta']");
    expect(element?.className).toContain("custom-class");
  });

  it("should pass through additional HTML attributes", () => {
    const { container } = render(
      <Cta title="Attrs" id="my-cta" aria-label="call to action" />,
    );
    const element = container.querySelector("[data-testid='cta']");
    expect(element?.getAttribute("id")).toBe("my-cta");
    expect(element?.getAttribute("aria-label")).toBe("call to action");
  });
});

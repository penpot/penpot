/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { render } from "@testing-library/react";

import { Heading } from "./Heading";

describe("Heading", () => {
  it("should render successfully", () => {
    const { baseElement } = render(
      <Heading typography="display">Hello</Heading>,
    );
    expect(baseElement).toBeTruthy();
  });

  it("should render as h1 by default", () => {
    const { container } = render(<Heading typography="display">Hello</Heading>);
    const element = container.querySelector("h1");
    expect(element).toBeTruthy();
    expect(element?.textContent).toBe("Hello");
  });

  it("should render the correct heading level", () => {
    const { container } = render(
      <Heading level={3} typography="title-medium">
        Level 3
      </Heading>,
    );
    const element = container.querySelector("h3");
    expect(element).toBeTruthy();
    expect(element?.textContent).toBe("Level 3");
  });

  it("should apply typography class", () => {
    const { container } = render(
      <Heading typography="title-large">Styled</Heading>,
    );
    const element = container.querySelector("h1");
    expect(element?.className).toContain("title-large-typography");
  });

  it("should merge custom className with typography class", () => {
    const { container } = render(
      <Heading typography="display" className="custom-class">
        Merged
      </Heading>,
    );
    const element = container.querySelector("h1");
    expect(element?.className).toContain("display-typography");
    expect(element?.className).toContain("custom-class");
  });

  it("should pass through additional HTML attributes", () => {
    const { container } = render(
      <Heading typography="body-small" data-testid="my-heading" id="heading-1">
        Attrs
      </Heading>,
    );
    const element = container.querySelector("h1");
    expect(element?.getAttribute("data-testid")).toBe("my-heading");
    expect(element?.getAttribute("id")).toBe("heading-1");
  });

  it("should render all heading levels (1-6)", () => {
    const levels = [1, 2, 3, 4, 5, 6] as const;
    for (const level of levels) {
      const { container } = render(
        <Heading level={level} typography="body-medium">
          H{level}
        </Heading>,
      );
      const element = container.querySelector(`h${level}`);
      expect(element).toBeTruthy();
      expect(element?.textContent).toBe(`H${level}`);
    }
  });
});

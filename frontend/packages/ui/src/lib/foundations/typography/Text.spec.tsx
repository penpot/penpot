/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */

import { render } from "@testing-library/react";

import { Text } from "./Text";

describe("Text", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Text typography="body-medium">Hello</Text>);
    expect(baseElement).toBeTruthy();
  });

  it("should render with default 'p' tag", () => {
    const { container } = render(<Text typography="body-medium">Hello</Text>);
    const element = container.querySelector("p");
    expect(element).toBeTruthy();
    expect(element?.textContent).toBe("Hello");
  });

  it("should render with a custom tag via 'as' prop", () => {
    const { container } = render(
      <Text as="span" typography="display">
        Custom
      </Text>,
    );
    const element = container.querySelector("span");
    expect(element).toBeTruthy();
    expect(element?.textContent).toBe("Custom");
  });

  it("should apply typography class", () => {
    const { container } = render(<Text typography="body-medium">Styled</Text>);
    const element = container.querySelector("p");
    expect(element?.className).toContain("body-medium-typography");
  });

  it("should merge custom className with typography class", () => {
    const { container } = render(
      <Text typography="display" className="custom-class">
        Merged
      </Text>,
    );
    const element = container.querySelector("p");
    expect(element?.className).toContain("display-typography");
    expect(element?.className).toContain("custom-class");
  });

  it("should pass through additional HTML attributes", () => {
    const { container } = render(
      <Text typography="body-small" data-testid="my-text" id="text-1">
        Attrs
      </Text>,
    );
    const element = container.querySelector("p");
    expect(element?.getAttribute("data-testid")).toBe("my-text");
    expect(element?.getAttribute("id")).toBe("text-1");
  });
});

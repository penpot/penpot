// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Swatch } from "./Swatch";

describe("Swatch", () => {
  it("should render as a div when no onClick is provided", () => {
    const { container } = render(<Swatch background={{ color: "#ff0000" }} />);
    const el = container.firstElementChild;
    expect(el?.tagName.toLowerCase()).toBe("div");
  });

  it("should render as a button when onClick is provided", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000" }} onClick={() => {}} />,
    );
    const el = container.firstElementChild;
    expect(el?.tagName.toLowerCase()).toBe("button");
  });

  it("should apply small size class by default", () => {
    const { container } = render(<Swatch background={{ color: "#ff0000" }} />);
    const el = container.firstElementChild;
    expect(el?.getAttribute("class")).toContain("small");
  });

  it("should apply medium size class when size='medium'", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000" }} size="medium" />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "medium",
    );
  });

  it("should apply large size class when size='large'", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000" }} size="large" />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "large",
    );
  });

  it("should apply active class when active=true", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000" }} active />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "active",
    );
  });

  it("should apply rounded class when background has refId", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000", refId: "some-id" }} />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "rounded",
    );
  });

  it("should apply square class when background has no refId", () => {
    const { container } = render(<Swatch background={{ color: "#ff0000" }} />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "square",
    );
  });

  it("should apply interactive class when onClick is provided", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000" }} onClick={() => {}} />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "interactive",
    );
  });

  it("should call onClick with background and event when clicked", () => {
    const bg = { color: "#ff0000" };
    const onClickMock = vi.fn();
    const { container } = render(
      <Swatch background={bg} onClick={onClickMock} />,
    );
    const button = container.firstElementChild as HTMLButtonElement;
    button.click();
    expect(onClickMock).toHaveBeenCalledTimes(1);
    expect(onClickMock.mock.calls[0][0]).toBe(bg);
  });

  it("should render gradient inner div when gradient is provided", () => {
    const { container } = render(
      <Swatch
        background={{
          gradient: {
            type: "linear",
            stops: [
              { color: "#000000", opacity: 1, offset: 0 },
              { color: "#ffffff", opacity: 1, offset: 1 },
            ],
          },
        }}
      />,
    );
    const inner = container.querySelector("[class*='swatch-gradient']");
    expect(inner).toBeTruthy();
  });

  it("should render image inner div when imageUri is provided", () => {
    const { container } = render(
      <Swatch background={{ imageUri: "https://example.com/img.png" }} />,
    );
    const inner = container.querySelector("[class*='swatch-image']");
    expect(inner).toBeTruthy();
  });

  it("should render error inner div when hasErrors=true", () => {
    const { container } = render(<Swatch hasErrors />);
    const inner = container.querySelector("[class*='swatch-error']");
    expect(inner).toBeTruthy();
  });

  it("should forward className to the root element", () => {
    const { container } = render(
      <Swatch background={{ color: "#ff0000" }} className="custom-cls" />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "custom-cls",
    );
  });
});

// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { HintMessage } from "./HintMessage";

describe("HintMessage", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<HintMessage id="field" message="A hint" />);
    expect(baseElement).toBeTruthy();
  });

  it("should render a <div> root element", () => {
    const { container } = render(<HintMessage id="field" message="A hint" />);
    expect(container.firstElementChild?.tagName).toBe("DIV");
  });

  it("should render the message in a span", () => {
    const { container } = render(
      <HintMessage id="field" message="Some hint text" />,
    );
    const span = container.querySelector("span");
    expect(span?.textContent).toBe("Some hint text");
  });

  it("should set span id to `${id}-hint`", () => {
    const { container } = render(<HintMessage id="my-field" message="hint" />);
    const span = container.querySelector("span");
    expect(span?.getAttribute("id")).toBe("my-field-hint");
  });

  it("should not render span when message is undefined", () => {
    const { container } = render(<HintMessage id="field" />);
    expect(container.querySelector("span")).toBeNull();
  });

  it("should not set aria-live for type hint (default)", () => {
    const { container } = render(<HintMessage id="field" message="hint" />);
    const div = container.firstElementChild;
    expect(div?.getAttribute("aria-live")).toBeNull();
  });

  it("should set aria-live=polite for type warning", () => {
    const { container } = render(
      <HintMessage id="field" message="warning" type="warning" />,
    );
    const div = container.firstElementChild;
    expect(div?.getAttribute("aria-live")).toBe("polite");
  });

  it("should set aria-live=polite for type error", () => {
    const { container } = render(
      <HintMessage id="field" message="error" type="error" />,
    );
    const div = container.firstElementChild;
    expect(div?.getAttribute("aria-live")).toBe("polite");
  });

  it("should merge className", () => {
    const { container } = render(
      <HintMessage id="field" message="hint" className="extra" />,
    );
    const div = container.firstElementChild;
    expect(div?.getAttribute("class")).toContain("extra");
  });

  it("should pass through HTML attributes", () => {
    const { container } = render(
      <HintMessage id="field" message="hint" data-testid="h" />,
    );
    const div = container.firstElementChild;
    expect(div?.getAttribute("data-testid")).toBe("h");
  });
});

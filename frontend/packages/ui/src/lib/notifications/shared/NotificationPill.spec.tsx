// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { NotificationPill } from "./NotificationPill";

describe("NotificationPill", () => {
  it("should render successfully", () => {
    const { baseElement } = render(
      <NotificationPill level="info" type="context">
        A message
      </NotificationPill>,
    );
    expect(baseElement).toBeTruthy();
  });

  it("should render children text content", () => {
    const { getByText } = render(
      <NotificationPill level="info" type="context">
        Hello notification
      </NotificationPill>,
    );
    expect(getByText("Hello notification")).toBeTruthy();
  });

  it("should render an icon element", () => {
    const { baseElement } = render(
      <NotificationPill level="info" type="context">
        msg
      </NotificationPill>,
    );
    const svg = baseElement.querySelector("svg");
    expect(svg).toBeTruthy();
  });

  it("should apply level-error class for error level", () => {
    const { baseElement } = render(
      <NotificationPill level="error" type="context">
        msg
      </NotificationPill>,
    );
    const root = baseElement.firstElementChild?.firstElementChild;
    expect(root?.getAttribute("class")).toMatch(/level-error/);
  });

  it("should apply level-warning class for warning level", () => {
    const { baseElement } = render(
      <NotificationPill level="warning" type="context">
        msg
      </NotificationPill>,
    );
    const root = baseElement.firstElementChild?.firstElementChild;
    expect(root?.getAttribute("class")).toMatch(/level-warning/);
  });

  it("should apply level-success class for success level", () => {
    const { baseElement } = render(
      <NotificationPill level="success" type="context">
        msg
      </NotificationPill>,
    );
    const root = baseElement.firstElementChild?.firstElementChild;
    expect(root?.getAttribute("class")).toMatch(/level-success/);
  });

  it("should apply type-toast class when type is toast", () => {
    const { baseElement } = render(
      <NotificationPill level="info" type="toast">
        msg
      </NotificationPill>,
    );
    const root = baseElement.firstElementChild?.firstElementChild;
    expect(root?.getAttribute("class")).toMatch(/type-toast/);
  });

  it("should apply appearance-ghost class when appearance is ghost", () => {
    const { baseElement } = render(
      <NotificationPill level="info" type="context" appearance="ghost">
        msg
      </NotificationPill>,
    );
    const root = baseElement.firstElementChild?.firstElementChild;
    expect(root?.getAttribute("class")).toMatch(/appearance-ghost/);
  });

  it("should not render detail section when detail is not provided", () => {
    const { queryByText } = render(
      <NotificationPill level="info" type="context">
        msg
      </NotificationPill>,
    );
    expect(queryByText("Detail")).toBeNull();
  });

  it("should render detail toggle button when detail is provided", () => {
    const { getByText } = render(
      <NotificationPill
        level="info"
        type="context"
        detail="<p>Details here</p>"
      >
        msg
      </NotificationPill>,
    );
    expect(getByText("Detail")).toBeTruthy();
  });

  it("should call onToggleDetail when detail title is clicked", () => {
    const onToggleDetail = vi.fn();
    const { getByText } = render(
      <NotificationPill
        level="info"
        type="context"
        detail="<p>Details here</p>"
        showDetail={false}
        onToggleDetail={onToggleDetail}
      >
        msg
      </NotificationPill>,
    );
    fireEvent.click(getByText("Detail"));
    expect(onToggleDetail).toHaveBeenCalledTimes(1);
  });

  it("should render detail content when showDetail is true", () => {
    const { baseElement } = render(
      <NotificationPill
        level="info"
        type="context"
        detail="<p>Secret details</p>"
        showDetail={true}
      >
        msg
      </NotificationPill>,
    );
    expect(baseElement.innerHTML).toContain("Secret details");
  });

  it("should not render detail content when showDetail is false", () => {
    const { baseElement } = render(
      <NotificationPill
        level="info"
        type="context"
        detail="<p>Secret details</p>"
        showDetail={false}
      >
        msg
      </NotificationPill>,
    );
    expect(baseElement.innerHTML).not.toContain("Secret details");
  });

  it("should inject HTML when isHtml is true", () => {
    const { baseElement } = render(
      <NotificationPill level="info" type="context" isHtml>
        {"<strong>Bold text</strong>"}
      </NotificationPill>,
    );
    expect(baseElement.querySelector("strong")).toBeTruthy();
  });
});

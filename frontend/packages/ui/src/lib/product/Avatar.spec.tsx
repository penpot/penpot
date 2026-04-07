// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Avatar } from "./Avatar";

const profile = { fullname: "Ada Lovelace" };
const profileWithPhoto = {
  fullname: "Ada Lovelace",
  photoUrl: "https://example.com/photo.jpg",
};

describe("Avatar", () => {
  it("should render successfully", () => {
    const { baseElement } = render(<Avatar profile={profile} />);
    expect(baseElement).toBeTruthy();
  });

  it("should render a div root by default", () => {
    const { container } = render(<Avatar profile={profile} />);
    expect(container.firstElementChild?.tagName.toLowerCase()).toBe("div");
  });

  it("should render with a custom tag", () => {
    const { container } = render(<Avatar profile={profile} tag="button" />);
    expect(container.firstElementChild?.tagName.toLowerCase()).toBe("button");
  });

  it("should apply avatar class to root element", () => {
    const { container } = render(<Avatar profile={profile} />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "avatar",
    );
  });

  it("should apply avatar-small class by default", () => {
    const { container } = render(<Avatar profile={profile} />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "avatar-small",
    );
  });

  it("should apply avatar-medium class when variant='M'", () => {
    const { container } = render(<Avatar profile={profile} variant="M" />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "avatar-medium",
    );
  });

  it("should apply avatar-large class when variant='L'", () => {
    const { container } = render(<Avatar profile={profile} variant="L" />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "avatar-large",
    );
  });

  it("should apply is-selected class when selected=true", () => {
    const { container } = render(<Avatar profile={profile} selected />);
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "is-selected",
    );
  });

  it("should set title to profile fullname on root element", () => {
    const { container } = render(<Avatar profile={profile} />);
    expect(container.firstElementChild?.getAttribute("title")).toBe(
      "Ada Lovelace",
    );
  });

  it("should render img with alt text from fullname", () => {
    const { container } = render(<Avatar profile={profile} />);
    const img = container.querySelector("img");
    expect(img?.getAttribute("alt")).toBe("Ada Lovelace");
  });

  it("should use photoUrl as img src when provided", () => {
    const { container } = render(<Avatar profile={profileWithPhoto} />);
    const img = container.querySelector("img");
    expect(img?.getAttribute("src")).toBe("https://example.com/photo.jpg");
  });

  it("should generate a data URI src when no photoUrl provided", () => {
    const { container } = render(<Avatar profile={profile} />);
    const img = container.querySelector("img");
    expect(img?.getAttribute("src")).toMatch(/^data:image\/svg\+xml;base64,/);
  });

  it("should forward className to the root element", () => {
    const { container } = render(
      <Avatar profile={profile} className="custom-cls" />,
    );
    expect(container.firstElementChild?.getAttribute("class")).toContain(
      "custom-cls",
    );
  });
});

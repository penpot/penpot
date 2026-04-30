// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render, fireEvent } from "@testing-library/react";
import { TabSwitcher } from "./TabSwitcher";
import type { TabItem } from "./TabSwitcher";

const tabs: TabItem[] = [
  { id: "tab-code", label: "Code" },
  { id: "tab-design", label: "Design" },
  { id: "tab-menu", label: "Menu" },
];

describe("TabSwitcher", () => {
  it("should render successfully", () => {
    const { baseElement } = render(
      <TabSwitcher tabs={tabs} selected="tab-code" onTabChange={vi.fn()} />,
    );
    expect(baseElement).toBeTruthy();
  });

  it("should render all tab buttons", () => {
    const { getByRole, getAllByRole } = render(
      <TabSwitcher tabs={tabs} selected="tab-code" onTabChange={vi.fn()} />,
    );
    const tablist = getByRole("tablist");
    expect(tablist).toBeTruthy();
    const tabButtons = getAllByRole("tab");
    expect(tabButtons).toHaveLength(3);
  });

  it("should mark the selected tab as aria-selected", () => {
    const { getAllByRole } = render(
      <TabSwitcher tabs={tabs} selected="tab-design" onTabChange={vi.fn()} />,
    );
    const tabButtons = getAllByRole("tab");
    const designTab = tabButtons.find(
      (btn) => btn.textContent?.trim() === "Design",
    ) as HTMLButtonElement;
    expect(designTab.getAttribute("aria-selected")).toBe("true");
  });

  it("should mark other tabs as aria-selected=false", () => {
    const { getAllByRole } = render(
      <TabSwitcher tabs={tabs} selected="tab-design" onTabChange={vi.fn()} />,
    );
    const tabButtons = getAllByRole("tab");
    const codeTab = tabButtons.find(
      (btn) => btn.textContent?.trim() === "Code",
    ) as HTMLButtonElement;
    expect(codeTab.getAttribute("aria-selected")).toBe("false");
  });

  it("should call onTabChange when a tab is clicked", () => {
    const handleChange = vi.fn();
    const { getAllByRole } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={handleChange}
      />,
    );
    const tabButtons = getAllByRole("tab");
    const designTab = tabButtons.find(
      (btn) => btn.textContent?.trim() === "Design",
    ) as HTMLButtonElement;
    fireEvent.click(designTab);
    expect(handleChange).toHaveBeenCalledWith("tab-design");
  });

  it("should render a tabpanel", () => {
    const { getByRole } = render(
      <TabSwitcher tabs={tabs} selected="tab-code" onTabChange={vi.fn()}>
        <p>Content</p>
      </TabSwitcher>,
    );
    const panel = getByRole("tabpanel");
    expect(panel).toBeTruthy();
  });

  it("should render children inside the tab panel", () => {
    const { getByText } = render(
      <TabSwitcher tabs={tabs} selected="tab-code" onTabChange={vi.fn()}>
        <p>Tab content here</p>
      </TabSwitcher>,
    );
    expect(getByText("Tab content here")).toBeTruthy();
  });

  it("should navigate with ArrowRight key", () => {
    const handleChange = vi.fn();
    const { getByRole } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={handleChange}
      />,
    );
    const tablist = getByRole("tablist");
    fireEvent.keyDown(tablist, { key: "ArrowRight" });
    expect(handleChange).toHaveBeenCalledWith("tab-design");
  });

  it("should navigate with ArrowLeft key", () => {
    const handleChange = vi.fn();
    const { getByRole } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={handleChange}
      />,
    );
    const tablist = getByRole("tablist");
    fireEvent.keyDown(tablist, { key: "ArrowLeft" });
    // wraps around to last tab
    expect(handleChange).toHaveBeenCalledWith("tab-menu");
  });

  it("should navigate with Home key", () => {
    const handleChange = vi.fn();
    const { getByRole } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-design"
        onTabChange={handleChange}
      />,
    );
    const tablist = getByRole("tablist");
    fireEvent.keyDown(tablist, { key: "Home" });
    expect(handleChange).toHaveBeenCalledWith("tab-code");
  });

  it("should pass className to wrapper", () => {
    const { container } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={vi.fn()}
        className="my-class"
      />,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("class")).toContain("my-class");
  });

  it("should spread extra props onto wrapper div", () => {
    const { container } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={vi.fn()}
        data-testid="wrapper"
      />,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("data-testid")).toBe("wrapper");
  });

  it("should render action button in start position", () => {
    const { container } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={vi.fn()}
        actionButtonPosition="start"
        actionButton={<button>Action</button>}
      />,
    );
    const nav = container.querySelector("nav") as HTMLElement;
    const navClass = nav.getAttribute("class") ?? "";
    expect(navClass).toContain("tab-nav-start");
  });

  it("should render action button in end position", () => {
    const { container } = render(
      <TabSwitcher
        tabs={tabs}
        selected="tab-code"
        onTabChange={vi.fn()}
        actionButtonPosition="end"
        actionButton={<button>Action</button>}
      />,
    );
    const nav = container.querySelector("nav") as HTMLElement;
    const navClass = nav.getAttribute("class") ?? "";
    expect(navClass).toContain("tab-nav-end");
  });

  it("should set aria-labelledby on tabpanel to selected tab id", () => {
    const { getByRole } = render(
      <TabSwitcher tabs={tabs} selected="tab-code" onTabChange={vi.fn()} />,
    );
    const panel = getByRole("tabpanel");
    expect(panel.getAttribute("aria-labelledby")).toBe("tab-code");
  });
});

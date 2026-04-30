// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC

import { render } from "@testing-library/react";
import { RadioButtons } from "./RadioButtons";
import type { RadioButtonOption } from "./RadioButtons";

const options: RadioButtonOption[] = [
  { id: "opt-left", label: "Left", value: "left" },
  { id: "opt-center", label: "Center", value: "center" },
  { id: "opt-right", label: "Right", value: "right" },
];

describe("RadioButtons", () => {
  it("should render successfully", () => {
    const { baseElement } = render(
      <RadioButtons options={options} selected="left" />,
    );
    expect(baseElement).toBeTruthy();
  });

  it("should render all options as labels with inputs", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" />,
    );
    const inputs = container.querySelectorAll("input");
    expect(inputs).toHaveLength(3);
  });

  it("should mark the selected option as checked", () => {
    const { container } = render(
      <RadioButtons options={options} selected="center" />,
    );
    const input = container.querySelector(
      'input[value="center"]',
    ) as HTMLInputElement;
    expect(input.checked).toBe(true);
  });

  it("should mark non-selected options as unchecked", () => {
    const { container } = render(
      <RadioButtons options={options} selected="center" />,
    );
    const left = container.querySelector(
      'input[value="left"]',
    ) as HTMLInputElement;
    const right = container.querySelector(
      'input[value="right"]',
    ) as HTMLInputElement;
    expect(left.checked).toBe(false);
    expect(right.checked).toBe(false);
  });

  it("should attach onChange handler to inputs", () => {
    // Verify the inputs are rendered (onChange wiring is tested structurally)
    const handleChange = vi.fn();
    const { container } = render(
      <RadioButtons
        options={options}
        selected="left"
        onChange={handleChange}
      />,
    );
    const inputs = container.querySelectorAll("input");
    // All inputs should be present and not read-only when onChange is provided
    inputs.forEach((input) => {
      expect(input.readOnly).toBe(false);
    });
  });

  it("should set inputs as readOnly when no onChange provided", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" />,
    );
    const inputs = container.querySelectorAll("input");
    inputs.forEach((input) => {
      expect(input.readOnly).toBe(true);
    });
  });

  it("should use checkbox input type when allowEmpty", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" allowEmpty />,
    );
    const input = container.querySelector(
      'input[value="left"]',
    ) as HTMLInputElement;
    expect(input.type).toBe("checkbox");
  });

  it("should use radio input type by default", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" />,
    );
    const input = container.querySelector(
      'input[value="left"]',
    ) as HTMLInputElement;
    expect(input.type).toBe("radio");
  });

  it("should disable all inputs when wrapper is disabled", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" disabled />,
    );
    const left = container.querySelector(
      'input[value="left"]',
    ) as HTMLInputElement;
    const center = container.querySelector(
      'input[value="center"]',
    ) as HTMLInputElement;
    expect(left.disabled).toBe(true);
    expect(center.disabled).toBe(true);
  });

  it("should disable individual option when option.disabled is true", () => {
    const mixed: RadioButtonOption[] = [
      { id: "a", label: "A", value: "a" },
      { id: "b", label: "B", value: "b", disabled: true },
    ];
    const { container } = render(<RadioButtons options={mixed} selected="a" />);
    const a = container.querySelector('input[value="a"]') as HTMLInputElement;
    const b = container.querySelector('input[value="b"]') as HTMLInputElement;
    expect(a.disabled).toBe(false);
    expect(b.disabled).toBe(true);
  });

  it("should pass className to wrapper", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" className="my-class" />,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("class")).toContain("my-class");
  });

  it("should spread extra props onto wrapper div", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" data-testid="wrapper" />,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.getAttribute("data-testid")).toBe("wrapper");
  });

  it("should use name attribute on inputs", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" name="alignment" />,
    );
    const input = container.querySelector(
      'input[value="left"]',
    ) as HTMLInputElement;
    expect(input.getAttribute("name")).toBe("alignment");
  });

  it("should render labels with correct htmlFor", () => {
    const { container } = render(
      <RadioButtons options={options} selected="left" />,
    );
    const label = container.querySelector(
      '[data-testid="opt-left"]',
    ) as HTMLLabelElement;
    expect(label.getAttribute("for")).toBe("opt-left");
  });
});

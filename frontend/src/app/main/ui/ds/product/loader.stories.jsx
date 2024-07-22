import * as React from "react";
import Components from "@target/components";

const { Loader } = Components;

export default {
  title: "Product/Loader",
  component: Components.Loader,
};

export const Default = {
  render: () => <Loader title="Loading" />,
};

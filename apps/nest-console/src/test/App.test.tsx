import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { App } from "../App";

describe("App scaffold", () => {
  it("renders scaffold heading", () => {
    render(<App />);
    expect(screen.getByText("nest console scaffold")).toBeInTheDocument();
  });
});

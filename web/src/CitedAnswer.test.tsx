import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { CitedAnswer } from "./CitedAnswer";
import type { Source } from "./types";
describe("citation rendering", () => {
  it("links only known source labels and escapes document HTML", () => {
    const source: Source = {
      label: "S1",
      id: "d:0",
      documentId: "d",
      filename: "a",
      chunkIndex: 0,
      start: 0,
      end: 1,
      text: "x",
      score: 1,
    };
    const html = renderToStaticMarkup(
      <CitedAnswer text={"<script> [S1] [S9]"} sources={[source]} />,
    );
    expect(html).toContain("Open source S1");
    expect(html).not.toContain("Open source S9");
    expect(html).toContain("&lt;script&gt;");
  });

  it("links each label inside a grouped marker", () => {
    const make = (label: string, chunkIndex: number): Source => ({
      label,
      id: `d:${chunkIndex}`,
      documentId: "d",
      filename: "a",
      chunkIndex,
      start: 0,
      end: 1,
      text: "x",
      score: 1,
    });
    const html = renderToStaticMarkup(
      <CitedAnswer
        text={"Refunds take 30 days [S1, S2]. See also [S2,S7]."}
        sources={[make("S1", 0), make("S2", 1)]}
      />,
    );
    expect(html).toContain("Open source S1");
    expect(html).toContain("Open source S2");
    expect(html).not.toContain("Open source S7");
    expect(html).toContain("[S7]");
    expect(html).not.toContain("S1, S2");
  });
});

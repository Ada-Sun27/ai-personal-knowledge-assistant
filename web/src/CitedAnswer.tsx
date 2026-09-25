import type { Source } from "./types";

/** Matches [S1] and grouped markers such as [S1, S3]. */
const MARKER = /(\[S\d+(?:\s*,\s*S\d+)*\])/;
const IS_MARKER = /^\[S\d+(?:\s*,\s*S\d+)*\]$/;

export function CitedAnswer({
  text,
  sources,
}: {
  text: string;
  sources: Source[];
}) {
  const labels = new Set(sources.map((source) => source.label));
  const showSource = (label: string) => {
    const element = document.getElementById(
      `source-${label}`,
    ) as HTMLDetailsElement | null;
    if (element) {
      element.open = true;
      element.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  };
  return (
    <div className="answer-text">
      {text.split(MARKER).map((part, index) => {
        if (!IS_MARKER.test(part)) return <span key={index}>{part}</span>;
        const cited = part
          .slice(1, -1)
          .split(",")
          .map((label) => label.trim());
        return (
          <span key={index}>
            {cited.map((label, position) =>
              labels.has(label) ? (
                <button
                  key={position}
                  className="citation"
                  title={`Open source ${label}`}
                  onClick={() => showSource(label)}
                >
                  {`[${label}]`}
                </button>
              ) : (
                <span key={position}>{`[${label}]`}</span>
              ),
            )}
          </span>
        );
      })}
    </div>
  );
}

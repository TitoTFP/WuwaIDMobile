export function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (char) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        char
      ]!,
  );
}
const paths: Record<string, string> = {
  home: '<path d="m3 10 9-7 9 7v10H3z"/><path d="M9 20v-7h6v7"/>',
  tools: '<path d="m14 6 4 4 3-3a7 7 0 0 1-9 9l-6 6-4-4 6-6a7 7 0 0 1 9-9z"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  refresh:
    '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 12-1l2 3M4 15l2 3a7 7 0 0 0 12-1"/>',
  game: '<rect x="3" y="6" width="18" height="13" rx="4"/><path d="M7 10v5m-2-2.5h4m7-2h.01m2 3h.01"/>',
  shield:
    '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
  box: '<path d="m3 7 9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10M7.5 5l9 4"/>',
  id: '<rect x="3" y="5" width="18" height="14" rx="2"/><circle cx="8.5" cy="10" r="1.5"/><path d="M6 15c.8-2.2 4.2-2.2 5 0m3-5h4m-4 4h4"/>',
  chevron: '<path d="m6 9 6 6 6-6"/>',
  alert: '<path d="M12 3 2.8 20h18.4L12 3ZM12 9v4m0 3h.01"/>',
  pending: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
  download: '<path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/>',
  external: '<path d="M14 3h7v7m0-7L10 14M10 4H3v17h17v-7"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  moon: '<path d="M20.5 13.2A8.7 8.7 0 0 1 10.8 3.5 9 9 0 1 0 20.5 13.2Z"/>',
  system: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
};
export function icon(name: string, className = ""): string {
  return `<svg class="icon ${className}" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] ?? paths.info}</svg>`;
}

export type StatusTone = "good" | "neutral" | "warning" | "danger";

export function statusBadge(label: string, tone: StatusTone): string {
  const symbol = tone === "good" ? "check" : tone === "neutral" ? "pending" : tone === "danger" ? "alert" : "info";
  return `<span class="status-badge ${tone}">${icon(symbol)}${escapeHtml(label)}</span>`;
}

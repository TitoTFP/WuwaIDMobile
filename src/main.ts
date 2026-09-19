import "./styles.css";

const appVersion = "0.3.4";

function renderVersion(): void {
  const footer = document.querySelector<HTMLElement>(".footer");
  if (footer === null) {
    return;
  }

  const version = document.createElement("span");
  version.textContent = `v${appVersion}`;
  footer.append(" • ", version);
}

window.addEventListener("DOMContentLoaded", renderVersion);

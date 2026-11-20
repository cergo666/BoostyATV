(() => {
  const repo = "cergo666/BoostyATV";

  const versionNodes = document.querySelectorAll("[data-version]");
  const sizeNodes = document.querySelectorAll("[data-size]");
  const dateNodes = document.querySelectorAll("[data-date]");
  const downloadNodes = document.querySelectorAll("[data-download]");

  const formatSize = (bytes) => {
    if (!Number.isFinite(bytes) || bytes <= 0) return "~3,2 МБ";
    const mb = bytes / (1024 * 1024);
    return `${mb.toFixed(mb >= 10 ? 0 : 1).replace(".", ",")} МБ`;
  };

  const formatDate = (value) => {
    if (!value) return "";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "";
    return new Intl.DateTimeFormat("ru-RU", {
      day: "numeric",
      month: "long",
      year: "numeric",
    }).format(date);
  };

  fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
    headers: { Accept: "application/vnd.github+json" },
  })
    .then((response) => (response.ok ? response.json() : null))
    .then((release) => {
      if (!release) return;
      const version = release.tag_name || "";
      if (version) versionNodes.forEach((node) => { node.textContent = version; });

      const published = formatDate(release.published_at);
      if (published) dateNodes.forEach((node) => { node.textContent = published; });

      const apk = (release.assets || []).find((asset) => asset.name === "BoostyATV.apk");
      if (!apk) return;

      sizeNodes.forEach((node) => { node.textContent = formatSize(apk.size); });
      downloadNodes.forEach((node) => { node.href = apk.browser_download_url; });
    })
    .catch(() => {
      /* Static fallback links keep working without the API. */
    });
})();

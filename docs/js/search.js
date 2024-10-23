(function (window, document) {
  "use strict";

  const search = (e) => {
    const results = window.searchIndex.search(e.target.value, {
      bool: "AND",
      expand: true,
    });

    const cleanResults = results.filter((r) => r.doc.title !== undefined)

    const resEl = document.getElementById("search-results");
    const noResultsEl = document.getElementById("no-results-found");

    resEl.innerHTML = "";
    if (cleanResults.length > 0) {
      resEl.style.display = "block";
      noResultsEl.style.display = "none";
      cleanResults.map((r) => {
        const { id, title, description } = r.doc;
        const el = document.createElement("li");
        resEl.appendChild(el);

        const h3 = document.createElement("h3");
        el.appendChild(h3);

        const a = document.createElement("a");
        // TODO: highlight the search term in the dest page
        a.setAttribute("href", id);
        a.textContent = title;
        h3.appendChild(a);

        // TODO: show an excerpt of the found page
        // const p = document.createElement("p");
        // p.textContent = description;
        // el.appendChild(p);
      });
    } else {
      resEl.style.display = "none";
      noResultsEl.style.display = "block";
    }
  };

  const hideSearch = (e) => {
    setTimeout(() => {
      e.target.value = "";
      const resEl = document.getElementById("search-results");
      const noResultsEl = document.getElementById("no-results-found");
      resEl.style.display = "none";
      noResultsEl.style.display = "none";
    }, 200);
  };

  fetch("/search-index.json").then((response) =>
    response.json().then((rawIndex) => {
      window.searchIndex = elasticlunr.Index.load(rawIndex);
      document.getElementById("search-field").addEventListener("input", search);
      document.getElementById("search-field").addEventListener("blur", hideSearch);
    })
  );
})(window, document);

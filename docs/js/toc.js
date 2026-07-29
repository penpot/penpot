(function (window, document) {
  "use strict";

  let titleEl;
  let tocEl;

  const titleClicked = (e) => {
    tocEl.classList.toggle('open');
  };

  window.addEventListener('load', () => {
    titleEl = document.getElementById("toc-title");
    tocEl = document.getElementById("toc");
    titleEl.addEventListener("click", titleClicked);
  });
})(window, document);

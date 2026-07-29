
(function (window, document) {
    "use strict";

    const getRepoStars = async () => {
      try {
        const response = await fetch("https://api.github.com/repos/penpot/penpot");
        const data = await response.json();
        return data.stargazers_count;
      } catch (error) {
        console.error("Error fetching repository data:", error);
        return null;
      }
    };
  
    const updateStarsCount = async () => {
      const starsCount = await getRepoStars();
      if (starsCount !== null) {
        const starsEl = document.getElementById("repo-stars");
        if (starsEl) {
          starsEl.textContent = `${starsCount}`;
        }
      }
    };
  
    window.addEventListener('load', () => {
      updateStarsCount();
    });
  
  })(window, document);
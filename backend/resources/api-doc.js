(function() {
  document.addEventListener("DOMContentLoaded", function(event) {
    const rows = document.querySelectorAll(".rpc-row-info");

    const onRowClick = (event) => {
      const target = event.currentTarget;
      for (let node of rows) {
        if (node !== target) {
          node.nextElementSibling.classList.add("hidden");
        } else {
          const sibling = target.nextElementSibling;

          if (sibling.classList.contains("hidden")) {
            sibling.classList.remove("hidden");
          } else {
            sibling.classList.add("hidden");
          }
        }
      }
    };

    for (let node of rows) {
      node.addEventListener("click", onRowClick);
    }

  });
})();

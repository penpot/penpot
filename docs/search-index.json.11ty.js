const elasticlunr = require("elasticlunr");

module.exports = {
  permalink: "/search-index.json",
  eleventyExcludeFromCollections: true,
  eleventyComputed: {},
  async data() {
    return {
      permalink: "/search-index.json",
      eleventyExcludeFromCollections: true,
    };
  },
  async render({ collections }) {
    var index = elasticlunr(function () {
      this.addField("title");
      this.addField("content");
      this.setRef("id");
    });

    collections.all.forEach((page) => {
      index.addDoc({
        id: page.url,
        title: page.data.title,
        content: page.templateContent,
      });
    });

    return JSON.stringify(index.toJSON());
  },
};

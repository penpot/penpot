import fr_FR from "date-fns/locale/fr";
import en_US from "date-fns/locale/en-US";

import format from "date-fns/format";
import formatDistanceToNow from "date-fns/formatDistanceToNow";

if (typeof self !== "undefined") { init(self); }
else if (typeof global !== "undefined") { init(global); }
else if (typeof window !== "undefined") { init(window); }
else { throw new Error("unsupported execution environment"); }

function init(g) {
  g.dateFns = {
    locales: {
      "default": en_US,
      "en": en_US,
      "en_US": en_US,
      "fr": fr_FR,
      "fr_FR": fr_FR
    },
    format,
    formatDistanceToNow,
  };
}

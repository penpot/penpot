;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.read-only-badge
  "The badge that says the file takes no edits: in the pages panel of a
  file the user may not edit, and over the changes of a comparison."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc read-only-badge*
  []
  [:span {:class (stl/css :badge)}
   [:> i/icon* {:icon-id i/lock :size "s" :class (stl/css :icon)}]
   [:> text* {:as "span" :typography t/body-small}
    (tr "labels.view-only")]])

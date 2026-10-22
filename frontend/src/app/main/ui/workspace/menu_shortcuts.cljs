;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.menu-shortcuts
  "The keys of a workspace shortcut, drawn at the end of a menu entry. A
  shortcut the user customized is tinted."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [rumext.v2 :as mf]))

(mf/defc shortcuts*
  [{:keys [id]}]
  (let [custom-shortcuts (mf/deref refs/custom-shortcuts)
        customized?      (let [c (get-in custom-shortcuts [:workspace id])]
                           (and c (not= c "")))]
    [:span {:class (stl/css :shortcut)}
     (for [sc (scd/split-sc (sc/get-effective-tooltip id custom-shortcuts))]
       [:span {:class (stl/css-case :shortcut-key true
                                    :customized-key customized?)
               :key sc}
        sc])]))

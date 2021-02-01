;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.viewer.shortcuts
  (:require
   [app.config :as cfg]
   [app.main.data.colors :as mdc]
   [app.main.data.shortcuts :as ds]
   [app.main.data.shortcuts :refer [c-mod]]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(def shortcuts
  {:increase-zoom {:tooltip "+"
                   :command "+"
                   :fn (st/emitf dv/increase-zoom)}

   :decrease-zoom {:tooltip "-"
                   :command "-"
                   :fn (st/emitf dv/decrease-zoom)}

   :select-all    {:tooltip (ds/meta "A")
                   :command (ds/c-mod "a")
                   :fn (st/emitf (dv/select-all))}

   :zoom-50       {:tooltip (ds/shift "0")
                   :command "shift+0"
                   :fn (st/emitf dv/zoom-to-50)}

   :reset-zoom    {:tooltip (ds/shift "1")
                   :command "shift+1"
                   :fn (st/emitf dv/reset-zoom)}

   :zoom-200      {:tooltip (ds/shift "2")
                   :command "shift+2"
                   :fn (st/emitf dv/zoom-to-200)}

   :next-frame    {:tooltip ds/left-arrow
                   :command "left"
                   :fn (st/emitf dv/select-prev-frame)}

   :prev-frame    {:tooltip ds/right-arrow
                   :command "right"
                   :fn (st/emitf dv/select-next-frame)}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.viewer.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]))

(def shortcuts
  {:increase-zoom      {:tooltip "+"
                        :command "+"
                        :fn (st/emitf dv/increase-zoom)}

   :decrease-zoom      {:tooltip "-"
                        :command "-"
                        :fn (st/emitf dv/decrease-zoom)}

   :select-all         {:tooltip (ds/meta "A")
                        :command (ds/c-mod "a")
                        :fn (st/emitf (dv/select-all))}

   :reset-zoom         {:tooltip (ds/shift "0")
                        :command "shift+0"
                        :fn (st/emitf dv/reset-zoom)}

   :toggle-zoom-style  {:tooltip "F"
                        :command "f"
                        :fn (st/emitf dv/toggle-zoom-style)}

   :toogle-fullscreen  {:tooltip (ds/shift "F")
                        :command "shift+f"
                        :fn (st/emitf dv/toggle-fullscreen)}

   :next-frame         {:tooltip ds/left-arrow
                        :command "left"
                        :fn (st/emitf dv/select-prev-frame)}

   :prev-frame         {:tooltip ds/right-arrow
                        :command "right"
                        :fn (st/emitf dv/select-next-frame)}

   :open-handoff       {:tooltip "G H"
                        :command "g h"
                        :fn #(st/emit! (dv/go-to-section :handoff))}

   :open-comments      {:tooltip "G C"
                        :command "g c"
                        :fn #(st/emit! (dv/go-to-section :comments))}

   :open-interactions  {:tooltip "G V"
                        :command "g v"
                        :fn #(st/emit! (dv/go-to-section :interactions))}

   :open-workspace     {:tooltip "G W"
                        :command "g w"
                        :fn #(st/emit! (dv/go-to-workspace))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

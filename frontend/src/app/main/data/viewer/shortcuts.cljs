;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.viewer.shortcuts
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]))

(def shortcuts
  {:increase-zoom      {:tooltip "+"
                        :command "+"
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/increase-zoom)}

   :decrease-zoom      {:tooltip "-"
                        :command "-"
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/decrease-zoom)}

   :select-all         {:tooltip (ds/meta "A")
                        :command (ds/c-mod "a")
                        :subsections [:general-viewer]
                        :fn #(st/emit! (dv/select-all))}

   :reset-zoom         {:tooltip (ds/shift "0")
                        :command "shift+0"
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/reset-zoom)}

   :toggle-zoom-style  {:tooltip "F"
                        :command "f"
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/toggle-zoom-style)}

   :toggle-fullscreen  {:tooltip (ds/shift "F")
                        :command ["shift+f" "alt+enter"]
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/toggle-fullscreen)}

   :prev-frame         {:tooltip ds/left-arrow
                        :command ["left" "up" "shift+enter" "pageup" "shift+space"]
                        :subsections [:general-viewer]
                        :fn #(st/emit! dv/select-prev-frame)}

   :next-frame         {:tooltip ds/right-arrow
                        :command ["right" "down" "enter" "pagedown" "space"]
                        :subsections [:general-viewer]
                        :fn #(st/emit! dv/select-next-frame)}

   :open-inspect       {:tooltip "G I"
                        :command "g i"
                        :subsections [:navigation-viewer]
                        :fn #(st/emit! (dv/go-to-section :inspect))}

   :open-comments      {:tooltip "G C"
                        :command "g c"
                        :subsections [:navigation-viewer]
                        :fn #(st/emit! (dv/go-to-section :comments))}

   :open-interactions  {:tooltip "G V"
                        :command "g v"
                        :subsections [:navigation-viewer]
                        :fn #(st/emit! (dv/go-to-section :interactions))}

   :open-workspace     {:tooltip "G W"
                        :command "g w"
                        :subsections [:navigation-viewer]
                        :fn #(st/emit! (dcm/go-to-workspace))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

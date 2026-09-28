;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.viewer.shortcuts
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer :as dv]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]))

(def shortcuts
  {:increase-zoom      {:tooltip "+"
                        :label (fn [] (tr "shortcuts.increase-zoom"))
                        :command "+"
                        :subsections [:zoom-viewer]
                        :section [:viewer]
                        :fn #(st/emit! dv/increase-zoom)}

   :toggle-theme    {:tooltip (ds/alt "M")
                     :label (fn [] (tr "shortcuts.toggle-theme"))
                     :command (ds/a-mod "m")
                     :section [:viewer]
                     :subsections [:generic]
                     :fn #(st/emit! (with-meta (du/toggle-theme)
                                      {::ev/origin "viewer:shortcuts"}))}

   :decrease-zoom      {:tooltip "-"
                        :label (fn [] (tr "shortcuts.decrease-zoom"))
                        :command "-"
                        :section [:viewer]
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/decrease-zoom)}

   :select-all         {:tooltip (ds/meta "A")
                        :label (fn [] (tr "shortcuts.select-all"))
                        :command (ds/c-mod "a")
                        :section [:viewer]
                        :subsections [:generic]
                        :fn #(st/emit! (dv/select-all))}

   :reset-zoom         {:tooltip (ds/shift "0")
                        :label (fn [] (tr "shortcuts.reset-zoom"))
                        :command "shift+0"
                        :section [:viewer]
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/reset-zoom)}

   :toggle-zoom-style  {:tooltip "F"
                        :label (fn [] (tr "shortcuts.toggle-zoom-style"))
                        :command "f"
                        :section [:viewer]
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/toggle-zoom-style)}

   :toggle-fullscreen  {:tooltip (ds/shift "F")
                        :label (fn [] (tr "shortcuts.toggle-fullscreen"))
                        :command ["shift+f" "alt+enter"]
                        :section [:viewer]
                        :subsections [:zoom-viewer]
                        :fn #(st/emit! dv/toggle-fullscreen)}

   :prev-frame         {:tooltip ds/left-arrow
                        :label (fn [] (tr "shortcuts.prev-frame"))
                        :command ["left" "up" "shift+enter" "pageup" "shift+space"]
                        :subsections [:generic]
                        :section [:viewer]
                        :fn #(st/emit! dv/select-prev-frame)}

   :next-frame         {:tooltip ds/right-arrow
                        :label (fn [] (tr "shortcuts.next-frame"))
                        :command ["right" "down" "enter" "pagedown" "space"]
                        :subsections [:generic]
                        :section [:viewer]
                        :fn #(st/emit! dv/select-next-frame)}

   :open-inspect       {:tooltip "G I"
                        :label (fn [] (tr "shortcuts.open-inspect"))
                        :command "g i"
                        :subsections [:navigation-viewer]
                        :section [:viewer]
                        :fn #(st/emit! (dv/go-to-section :inspect))}

   :open-comments      {:tooltip "G C"
                        :label (fn [] (tr "shortcuts.open-comments"))
                        :command "g c"
                        :subsections [:navigation-viewer]
                        :section [:viewer]
                        :fn #(st/emit! (dv/go-to-section :comments))}

   :open-interactions  {:tooltip "G V"
                        :label (fn [] (tr "shortcuts.open-interactions"))
                        :command "g v"
                        :subsections [:navigation-viewer]
                        :section [:viewer]
                        :fn #(st/emit! (dv/go-to-section :interactions))}

   :open-workspace     {:tooltip "G W"
                        :label (fn [] (tr "shortcuts.open-workspace"))
                        :command "g w"
                        :subsections [:navigation-viewer]
                        :section [:viewer]
                        :fn #(st/emit! (dcm/go-to-workspace))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

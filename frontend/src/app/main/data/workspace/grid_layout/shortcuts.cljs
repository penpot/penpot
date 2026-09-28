;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.grid-layout.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(defn esc-pressed []
  (ptk/reify ::esc-pressed
    ptk/WatchEvent
    (watch [_ state _]
      ;;  Not interrupt when we're editing a path
      (let [edition-id (or (get-in state [:workspace-drawing :object :id])
                           (get-in state [:workspace-local :edition]))
            path-edit-mode (get-in state [:workspace-local :edit-path edition-id :edit-mode])]
        (if-not (= :draw path-edit-mode)
          (rx/of :interrupt)
          (rx/empty))))))

(def shortcuts
  {:escape          {:tooltip (ds/esc)
                     :label (fn [] (tr "shortcuts.escape"))
                     :command ["escape" "enter" "v"]
                     :section [:workspace]
                     :fn #(st/emit! (esc-pressed))}

   :undo            {:tooltip (ds/meta "Z")
                     :label (fn [] (tr "shortcuts.undo"))
                     :command (ds/c-mod "z")
                     :section [:workspace]
                     :fn #(st/emit! dwu/undo)}

   :redo            {:tooltip (ds/meta "Y")
                     :label (fn [] (tr "shortcuts.redo"))
                     :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                     :section [:workspace]
                     :fn #(st/emit! dwu/redo)}

   ;; ZOOM

   :increase-zoom   {:tooltip "+"
                     :label (fn [] (tr "shortcuts.increase-zoom"))
                     :command "+"
                     :section [:workspace]
                     :fn #(st/emit! (dw/increase-zoom nil))}

   :decrease-zoom   {:tooltip "-"
                     :label (fn [] (tr "shortcuts.decrease-zoom"))
                     :command "-"
                     :section [:workspace]
                     :fn #(st/emit! (dw/decrease-zoom nil))}

   :reset-zoom      {:tooltip (ds/shift "0")
                     :label (fn [] (tr "shortcuts.reset-zoom"))
                     :command "shift+0"
                     :section [:workspace]
                     :fn #(st/emit! dw/reset-zoom)}

   :fit-all         {:tooltip (ds/shift "1")
                     :label (fn [] (tr "shortcuts.fit-all"))
                     :command "shift+1"
                     :section [:workspace]
                     :fn #(st/emit! dw/zoom-to-fit-all)}

   :zoom-selected   {:tooltip (ds/shift "2")
                     :label (fn [] (tr "shortcuts.zoom-selected"))
                     :command "shift+2"
                     :section [:workspace]
                     :fn #(st/emit! dw/zoom-to-selected-shape)}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.grid-layout.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
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
                     :command ["escape" "enter" "v"]
                     :fn #(st/emit! (esc-pressed))}

   :undo            {:tooltip (ds/meta "Z")
                     :command (ds/c-mod "z")
                     :fn #(st/emit! dwu/undo)}

   :redo            {:tooltip (ds/meta "Y")
                     :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                     :fn #(st/emit! dwu/redo)}

   ;; ZOOM

   :increase-zoom   {:tooltip "+"
                     :command "+"
                     :fn #(st/emit! (dw/increase-zoom nil))}

   :decrease-zoom   {:tooltip "-"
                     :command "-"
                     :fn #(st/emit! (dw/decrease-zoom nil))}

   :reset-zoom      {:tooltip (ds/shift "0")
                     :command "shift+0"
                     :fn #(st/emit! dw/reset-zoom)}

   :fit-all         {:tooltip (ds/shift "1")
                     :command "shift+1"
                     :fn #(st/emit! dw/zoom-to-fit-all)}

   :zoom-selected   {:tooltip (ds/shift "2")
                     :command "shift+2"
                     :fn #(st/emit! dw/zoom-to-selected-shape)}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

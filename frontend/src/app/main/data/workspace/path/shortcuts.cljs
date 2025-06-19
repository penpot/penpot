;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path :as drp]
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
    (watch [_ _ _]
      ;; Not interrupt when we're editing a path
      (rx/of :interrupt))))

(def shortcuts
  {:move-nodes      {:tooltip "M"
                     :command "m"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/change-edit-mode :move))}

   :draw-nodes      {:tooltip "P"
                     :command "p"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/change-edit-mode :draw))}

   :add-node        {:tooltip (ds/shift "+")
                     :command "shift++"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/add-node))}

   :delete-node     {:tooltip (ds/supr)
                     :command ["del" "backspace"]
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/remove-node))}

   :merge-nodes     {:tooltip (ds/meta "J")
                     :command (ds/c-mod "j")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/merge-nodes))}

   :join-nodes      {:tooltip "J"
                     :command "j"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/join-nodes))}

   :separate-nodes  {:tooltip "K"
                     :command "k"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/separate-nodes))}

   :make-corner     {:tooltip "X"
                     :command "x"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/make-corner))}

   :make-curve      {:tooltip "C"
                     :command "c"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/make-curve))}

   :snap-nodes      {:tooltip (ds/meta "'")
                     ;;https://github.com/ccampbell/mousetrap/issues/85
                     :command [(ds/c-mod "'") (ds/c-mod "219")]
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/toggle-snap))}

   :escape          {:tooltip (ds/esc)
                     :command ["escape" "enter" "v"]
                     :fn #(st/emit! (esc-pressed))}

   :undo            {:tooltip (ds/meta "Z")
                     :command (ds/c-mod "z")
                     :fn #(st/emit! (drp/undo-path))}

   :redo            {:tooltip (ds/meta "Y")
                     :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                     :fn #(st/emit! (drp/redo-path))}

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
                     :fn #(st/emit! dw/zoom-to-selected-shape)}

   ;; Arrow movement

   :move-fast-up    {:tooltip (ds/shift ds/up-arrow)
                     :command "shift+up"
                     :fn #(st/emit! (drp/move-selected :up true))}

   :move-fast-down  {:tooltip (ds/shift ds/down-arrow)
                     :command "shift+down"
                     :fn #(st/emit! (drp/move-selected :down true))}

   :move-fast-right {:tooltip (ds/shift ds/right-arrow)
                     :command "shift+right"
                     :fn #(st/emit! (drp/move-selected :right true))}

   :move-fast-left  {:tooltip (ds/shift ds/left-arrow)
                     :command "shift+left"
                     :fn #(st/emit! (drp/move-selected :left true))}

   :move-unit-up    {:tooltip ds/up-arrow
                     :command "up"
                     :fn #(st/emit! (drp/move-selected :up false))}

   :move-unit-down  {:tooltip ds/down-arrow
                     :command "down"
                     :fn #(st/emit! (drp/move-selected :down false))}

   :move-unit-left  {:tooltip ds/right-arrow
                     :command "right"
                     :fn #(st/emit! (drp/move-selected :right false))}

   :move-unit-right {:tooltip ds/left-arrow
                     :command "left"
                     :fn #(st/emit! (drp/move-selected :left false))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

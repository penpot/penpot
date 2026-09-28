;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.path.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path :as drp]
   [app.main.data.workspace.path.common :as drp.common]
   [app.main.data.workspace.path.state :as drp.state]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(defn esc-pressed
  "Maps Escape to finish, cancel, or exit for the current draw state."
  []
  (ptk/reify ::esc-pressed
    ptk/WatchEvent
    (watch [_ state _]
      (let [id       (drp.state/get-path-id state)
            pending? (some? (get-in state [:workspace-local :edit-path id :last-point]))
            edition  (get-in state [:workspace-local :edition])]
        (cond
          (and pending? (nil? edition))
          (rx/of (drp.common/finish-path))

          pending?
          (rx/of (drp.common/cancel-pending-segment))

          :else
          (rx/of :interrupt))))))

(def shortcuts
  {:move-nodes      {:tooltip "M"
                     :label (fn [] (tr "shortcuts.move-nodes"))
                     :command "m"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/change-edit-mode :move))}

   :draw-nodes      {:tooltip "P"
                     :label (fn [] (tr "shortcuts.draw-nodes"))
                     :command "p"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :overwrite true
                     :fn #(st/emit! (drp/change-edit-mode :draw))}

   :add-node        {:tooltip (ds/shift "+")
                     :label (fn [] (tr "shortcuts.add-node"))
                     :command "shift++"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/add-node))}

   :delete-node     {:tooltip (ds/supr)
                     :label (fn [] (tr "shortcuts.delete-node"))
                     :command ["del" "backspace"]
                     :subsections [:path-editor]
                     :section [:workspace]
                     :overwrite true
                     :fn #(st/emit! (drp/delete-selected))}

   :delete-node-and-segments {:tooltip (ds/shift (ds/supr))
                              :label (fn [] (tr "shortcuts.delete-node-and-segments"))
                              :command ["shift+del" "shift+backspace"]
                              :subsections [:path-editor]
                              :fn #(st/emit! (drp/delete-selected-with-segments))}

   :merge-nodes     {:tooltip (ds/meta "J")
                     :label (fn [] (tr "shortcuts.merge-nodes"))
                     :command (ds/c-mod "j")
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/merge-nodes))}

   :join-nodes      {:tooltip "J"
                     :label (fn [] (tr "shortcuts.join-nodes"))
                     :command "j"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/join-nodes))}

   :separate-nodes  {:tooltip "K"
                     :label (fn [] (tr "shortcuts.separate-nodes"))
                     :command "k"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :overwrite true
                     :fn #(st/emit! (drp/separate-nodes))}

   :make-corner     {:tooltip "X"
                     :label (fn [] (tr "shortcuts.make-corner"))
                     :command "x"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/make-corner))}

   :make-curve      {:tooltip "C"
                     :label (fn [] (tr "shortcuts.make-curve"))
                     :command "c"
                     :subsections [:path-editor]
                     :section [:workspace]
                     :overwrite true
                     :fn #(st/emit! (drp/make-curve))}

   :snap-nodes      {:tooltip (ds/meta "'")
                     :label (fn [] (tr "shortcuts.snap-nodes"))
                     ;;https://github.com/ccampbell/mousetrap/issues/85
                     :command [(ds/c-mod "'") (ds/c-mod "219")]
                     :subsections [:path-editor]
                     :section [:workspace]
                     :fn #(st/emit! (drp/toggle-snap))}

   :copy            {:tooltip (ds/meta "C")
                     :label (fn [] (tr "shortcuts.copy"))
                     :command (ds/c-mod "c")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/copy-selected-nodes))}

   :cut             {:tooltip (ds/meta "X")
                     :label (fn [] (tr "shortcuts.cut"))
                     :command (ds/c-mod "x")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/cut-selected-nodes))}

   :paste           {:tooltip (ds/meta "V")
                     :label (fn [] (tr "shortcuts.paste"))
                     :command (ds/c-mod "v")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/paste-nodes))}

   :duplicate       {:tooltip (ds/meta "D")
                     :label (fn [] (tr "shortcuts.duplicate"))
                     :command (ds/c-mod "d")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/duplicate-selected))}

   :select-all      {:tooltip (ds/meta "A")
                     :label (fn [] (tr "shortcuts.select-all"))
                     :command (ds/c-mod "a")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/select-all-nodes))}

   :deselect-all    {:tooltip (ds/meta (ds/shift "A"))
                     :label (fn [] (tr "shortcuts.deselect-all"))
                     :command (ds/c-mod "shift+a")
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/deselect-all))}

   :flip-horizontal {:tooltip (ds/shift "H")
                     :label (fn [] (tr "shortcuts.flip-horizontal"))
                     :command "shift+h"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/flip-nodes :horizontal))}

   :flip-vertical   {:tooltip (ds/shift "V")
                     :label (fn [] (tr "shortcuts.flip-vertical"))
                     :command "shift+v"
                     :subsections [:path-editor]
                     :fn #(st/emit! (drp/flip-nodes :vertical))}

   :escape          {:tooltip (ds/esc)
                     :label (fn [] (tr "shortcuts.escape"))
                     :command ["escape" "v"]
                     :section [:workspace]
                     :fn #(st/emit! (esc-pressed))}

   ;; Reuses the `:start-editing` key (instead of adding "enter" to
   ;; the `:escape` command above) so that merging this shortcut set
   ;; on top of the base workspace shortcuts (see `dsc/push-shortcuts`)
   ;; deterministically replaces the workspace's `enter` binding
   ;; (which enters path edit mode) instead of both ending up bound
   ;; to the same physical key at once.
   :start-editing   {:tooltip (ds/enter)
                     :label (fn [] (tr "shortcuts.start-editing"))
                     :command "enter"
                     :section [:workspace]
                     :overwrite true
                     :fn #(st/emit! (esc-pressed))}

   :undo            {:tooltip (ds/meta "Z")
                     :label (fn [] (tr "shortcuts.undo"))
                     :command (ds/c-mod "z")
                     :section [:workspace]
                     :fn #(st/emit! (drp/undo-path))}

   :redo            {:tooltip (ds/meta "Y")
                     :label (fn [] (tr "shortcuts.redo"))
                     :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                     :section [:workspace]
                     :fn #(st/emit! (drp/redo-path))}

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
                     :fn #(st/emit! dw/zoom-to-selected-shape)}

   ;; Arrow movement

   :move-fast-up    {:tooltip (ds/shift ds/up-arrow)
                     :label (fn [] (tr "shortcuts.move-fast-up"))
                     :command "shift+up"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :up true))}

   :move-fast-down  {:tooltip (ds/shift ds/down-arrow)
                     :label (fn [] (tr "shortcuts.move-fast-down"))
                     :command "shift+down"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :down true))}

   :move-fast-right {:tooltip (ds/shift ds/right-arrow)
                     :label (fn [] (tr "shortcuts.move-fast-right"))
                     :command "shift+right"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :right true))}

   :move-fast-left  {:tooltip (ds/shift ds/left-arrow)
                     :label (fn [] (tr "shortcuts.move-fast-left"))
                     :command "shift+left"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :left true))}

   :move-unit-up    {:tooltip ds/up-arrow
                     :label (fn [] (tr "shortcuts.move-unit-up"))
                     :command "up"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :up false))}

   :move-unit-down  {:tooltip ds/down-arrow
                     :label (fn [] (tr "shortcuts.move-unit-down"))
                     :command "down"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :down false))}

   :move-unit-left  {:tooltip ds/right-arrow
                     :label (fn [] (tr "shortcuts.move-unit-left"))
                     :command "right"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :right false))}

   :move-unit-right {:tooltip ds/left-arrow
                     :label (fn [] (tr "shortcuts.move-unit-right"))
                     :command "left"
                     :section [:workspace]
                     :fn #(st/emit! (drp/move-selected :left false))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.shortcuts
  (:require
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path :as drp]
   [app.main.store :as st]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts format https://github.com/ccampbell/mousetrap

(defn esc-pressed []
  (ptk/reify :esc-pressed
    ptk/WatchEvent
    (watch [_ state stream]
      ;;  Not interrupt when we're editing a path
      (let [edition-id (or (get-in state [:workspace-drawing :object :id])
                           (get-in state [:workspace-local :edition]))
            path-edit-mode (get-in state [:workspace-local :edit-path edition-id :edit-mode])]
        (if-not (= :draw path-edit-mode)
          (rx/of :interrupt (dw/deselect-all true))
          (rx/empty))))))

(def shortcuts
  {:move-nodes     {:tooltip "V"
                    :command "v"
                    :fn #(st/emit! (drp/change-edit-mode :move))}

   :draw-nodes     {:tooltip "P"
                    :command "p"
                    :fn #(st/emit! (drp/change-edit-mode :draw))}

   :add-node       {:tooltip "+"
                    :command "+"
                    :fn #(st/emit! (drp/add-node))}

   :delete-node    {:tooltip (ds/supr)
                    :command ["del" "backspace"]
                    :fn #(st/emit! (drp/remove-node))}

   :merge-nodes    {:tooltip (ds/meta "J")
                    :command (ds/c-mod "j")
                    :fn #(st/emit! (drp/merge-nodes))}

   :join-nodes     {:tooltip "J"
                    :command "j"
                    :fn #(st/emit! (drp/join-nodes))}

   :separate-nodes {:tooltip "K"
                    :command "k"
                    :fn #(st/emit! (drp/separate-nodes))}

   :make-corner    {:tooltip "B"
                    :command "b"
                    :fn #(st/emit! (drp/make-corner))}

   :make-curve     {:tooltip (ds/meta "B")
                    :command (ds/c-mod "b")
                    :fn #(st/emit! (drp/make-curve))}

   :snap-nodes     {:tooltip (ds/meta "'")
                    :command (ds/c-mod "'")
                    :fn #(st/emit! (drp/toggle-snap))}
   
   :escape        {:tooltip (ds/esc)
                   :command "escape"
                   :fn #(st/emit! (esc-pressed))}

   :start-editing {:tooltip (ds/enter)
                   :command "enter"
                   :fn #(st/emit! (dw/start-editing-selected))}

   :undo          {:tooltip (ds/meta "Z")
                   :command (ds/c-mod "z")
                   :fn #(st/emit! (drp/undo-path))}

   :redo          {:tooltip (ds/meta "Y")
                   :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                   :fn #(st/emit! (drp/redo-path))}
   })

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.shortcuts
  (:require
   [app.config :as cfg]
   [app.main.data.colors :as mdc]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [potok.core :as ptk]))

;; \u2318P

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Shortcuts impl https://github.com/ccampbell/mousetrap

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
  {:toggle-layers     {:tooltip (ds/meta "L")
                       :command (ds/c-mod "l")
                       :fn #(st/emit! (dw/go-to-layout :layers))}

   :toggle-assets     {:tooltip (ds/meta "I")
                       :command (ds/c-mod "i")
                       :fn #(st/emit! (dw/go-to-layout :assets))}
   
   :toggle-history    {:tooltip (ds/meta "H")
                       :command (ds/c-mod "h")
                       :fn #(st/emit! (dw/go-to-layout :document-history))}

   :toggle-palette    {:tooltip (ds/meta "P")
                       :command (ds/c-mod "p")
                       :fn #(st/emit! (dw/toggle-layout-flags :colorpalette))}

   :toggle-rules      {:tooltip (ds/meta-shift "R")
                       :command (ds/c-mod "shift+r")
                       :fn #(st/emit! (dw/toggle-layout-flags :rules))}
   
   :select-all        {:tooltip (ds/meta "A")
                       :command (ds/c-mod "a")
                       :fn #(st/emit! (dw/select-all))}

   :toggle-grid       {:tooltip (ds/meta "'")
                       :command (ds/c-mod "'")
                       :fn #(st/emit! (dw/toggle-layout-flags :display-grid))}

   :toggle-snap-grid  {:tooltip (ds/meta-shift "'")
                       :command (ds/c-mod "shift+'")
                       :fn #(st/emit! (dw/toggle-layout-flags :snap-grid))}

   :toggle-alignment  {:tooltip (ds/meta "\\")
                       :command (ds/c-mod "\\")
                       :fn #(st/emit! (dw/toggle-layout-flags :dynamic-alignment))}
   
   :increase-zoom      {:tooltip "+"
                        :command "+"
                        :fn #(st/emit! (dw/increase-zoom nil))}

   :decrease-zoom      {:tooltip "-"
                        :command "-"
                        :fn #(st/emit! (dw/decrease-zoom nil))}
   
   :group              {:tooltip (ds/meta "G")
                        :command (ds/c-mod "g")
                        :fn #(st/emit! dw/group-selected)}

   :ungroup            {:tooltip (ds/shift "G")
                        :command "shift+g"
                        :fn #(st/emit! dw/ungroup-selected)}

   :mask               {:tooltip (ds/meta "M")
                        :command (ds/c-mod "m")
                        :fn #(st/emit! dw/mask-group)}

   :unmask             {:tooltip (ds/meta-shift "M")
                        :command (ds/c-mod "shift+m")
                        :fn #(st/emit! dw/unmask-group)}

   :create-component   {:tooltip (ds/meta "K")
                        :command (ds/c-mod "k")
                        :fn #(st/emit! dwl/add-component)}

   :flip-vertical      {:tooltip (ds/shift "V")
                        :command "shift+v"
                        :fn #(st/emit! (dw/flip-vertical-selected))}

   :flip-horizontal    {:tooltip (ds/shift "V")
                        :command "shift+h"
                        :fn #(st/emit! (dw/flip-horizontal-selected))}

   :reset-zoom         {:tooltip (ds/shift "0")
                        :command "shift+0"
                        :fn #(st/emit! dw/reset-zoom)}

   :fit-all            {:tooltip (ds/shift "1")
                        :command "shift+1"
                        :fn #(st/emit! dw/zoom-to-fit-all)}

   :zoom-selected      {:tooltip (ds/shift "2")
                        :command "shift+2"
                        :fn #(st/emit! dw/zoom-to-selected-shape)}

   :duplicate          {:tooltip (ds/meta "D")
                        :command (ds/c-mod "d")
                        :fn #(st/emit! dw/duplicate-selected)}

   :undo               {:tooltip (ds/meta "Z")
                        :command (ds/c-mod "z")
                        :fn #(st/emit! dwc/undo)}

   :redo               {:tooltip (ds/meta "Y")
                        :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                        :fn #(st/emit! dwc/redo)}

   :clear-undo         {:tooltip (ds/meta "Q")
                        :command (ds/c-mod "q")
                        :fn #(st/emit! dwc/reinitialize-undo)}

   :draw-frame         {:tooltip "A"
                        :command "a"
                        :fn #(st/emit! (dwd/select-for-drawing :frame))}

   :draw-rect          {:tooltip "R"
                        :command "r"
                        :fn #(st/emit! (dwd/select-for-drawing :rect))}

   :draw-ellipse       {:tooltip "E"
                        :command "e"
                        :fn #(st/emit! (dwd/select-for-drawing :circle))}

   :draw-text          {:tooltip "T"
                        :command "t"
                        :fn #(st/emit! dwtxt/start-edit-if-selected
                                       (dwd/select-for-drawing :text))}

   :draw-path          {:tooltip "P"
                        :command "p"
                        :fn #(st/emit! (dwd/select-for-drawing :path))}

   :draw-curve         {:tooltip (ds/shift "C")
                        :command "shift+c"
                        :fn #(st/emit! (dwd/select-for-drawing :curve))}

   :add-comment        {:tooltip "C"
                        :command "c"
                        :fn #(st/emit! (dwd/select-for-drawing :comments))}

   :insert-image       {:tooltip "K"
                        :command "k"
                        :fn #(-> "image-upload" dom/get-element dom/click)}

   :copy               {:tooltip (ds/meta "C")
                        :command (ds/c-mod "c")
                        :fn #(st/emit! (dw/copy-selected))}

   :cut                {:tooltip (ds/meta "X")
                        :command (ds/c-mod "x")
                        :fn #(st/emit! (dw/copy-selected) dw/delete-selected)}

   :paste              {:tooltip (ds/meta "V")
                        :disabled true
                        :command (ds/c-mod "v")}

   :delete             {:tooltip (ds/supr)
                        :command ["del" "backspace"]
                        :fn #(st/emit! dw/delete-selected)}

   :bring-forward      {:tooltip (ds/meta ds/up-arrow)
                        :command (ds/c-mod "up")
                        :fn #(st/emit! (dw/vertical-order-selected :up))}

   :bring-backward     {:tooltip (ds/meta ds/down-arrow)
                        :command (ds/c-mod "down")
                        :fn #(st/emit! (dw/vertical-order-selected :down))}

   :bring-front        {:tooltip (ds/meta-shift ds/up-arrow)
                        :command (ds/c-mod "shift+up")
                        :fn #(st/emit! (dw/vertical-order-selected :top))}

   :bring-back         {:tooltip (ds/meta-shift ds/down-arrow)
                        :command (ds/c-mod "shift+down")
                        :fn #(st/emit! (dw/vertical-order-selected :bottom))}

   :move-fast-up       {:tooltip (ds/shift ds/up-arrow)
                        :command "shift+up"
                        :fn #(st/emit! (dwt/move-selected :up true))}

   :move-fast-down     {:tooltip (ds/shift ds/down-arrow)
                        :command "shift+down"
                        :fn #(st/emit! (dwt/move-selected :down true))}

   :move-fast-right    {:tooltip (ds/shift ds/right-arrow)
                        :command "shift+right"
                        :fn #(st/emit! (dwt/move-selected :right true))}

   :move-fast-left     {:tooltip (ds/shift ds/left-arrow)
                        :command "shift+left"
                        :fn #(st/emit! (dwt/move-selected :left true))}

   :move-unit-up       {:tooltip ds/up-arrow
                        :command "up"
                        :fn #(st/emit! (dwt/move-selected :up false))}

   :move-unit-down     {:tooltip ds/down-arrow
                        :command "down"
                        :fn #(st/emit! (dwt/move-selected :down false))}

   :move-unit-left     {:tooltip ds/right-arrow
                        :command "right"
                        :fn #(st/emit! (dwt/move-selected :right false))}

   :move-unit-right    {:tooltip ds/left-arrow
                        :command "left"
                        :fn #(st/emit! (dwt/move-selected :left false))}

   :open-color-picker  {:tooltip "I"
                        :command "i"
                        :fn #(st/emit! (mdc/picker-for-selected-shape ))}

   :open-viewer        {:tooltip "G V"
                        :command "g v"
                        :fn #(st/emit! (dw/go-to-viewer))}

   :open-dashboard     {:tooltip "G D"
                        :command "g d"
                        :fn #(st/emit! (dw/go-to-dashboard))}

   :escape             {:tooltip (ds/esc)
                        :command "escape"
                        :fn #(st/emit! (esc-pressed))}})

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

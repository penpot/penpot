;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.shortcuts
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.exports.assets :as de]
   [app.main.data.modal :as modal]
   [app.main.data.plugins :as dpl]
   [app.main.data.preview :as dp]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.layers :as dwly]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dws]
   [app.main.data.workspace.text.shortcuts :as dwtxts]
   [app.main.data.workspace.texts :as dwtxt]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.variants :as dwv]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.hooks.resize :as r]
   [app.util.dom :as dom]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- toggle-layout-flag
  [flag]
  (-> (dw/toggle-layout-flag flag)
      (vary-meta assoc ::ev/origin "workspace-shortcuts")))

(defn- emit-when-no-readonly
  [& events]
  (let [can-edit?  (:can-edit (deref refs/permissions))
        read-only? (deref refs/workspace-read-only?)]
    (when (and can-edit? (not read-only?))
      (run! st/emit! events))))

(def esc-pressed
  (ptk/reify ::esc-pressed
    ptk/WatchEvent
    (watch [_ state _]
      (rx/of
       :interrupt
       (let [selection (dm/get-in state [:workspace-local :selected])]
         (if (empty? selection)
           (dpl/close-current-plugin)
           (dw/deselect-all true)))))))

;; Shortcuts format https://github.com/ccampbell/mousetrap

(def base-shortcuts
  {;; EDIT
   :undo                 {:tooltip (ds/meta "Z")
                          :command (ds/c-mod "z")
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly dwu/undo)}

   :redo                 {:tooltip (ds/meta "Y")
                          :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly dwu/redo)}

   :clear-undo           {:tooltip (ds/alt "Q")
                          :command "alt+q"
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly dwu/reinitialize-undo)}

   :copy                 {:tooltip (ds/meta "C")
                          :command (ds/c-mod "c")
                          :subsections [:edit]
                          :fn #(st/emit! (dw/copy-selected))}

   :copy-link            {:tooltip (ds/shift (ds/alt "C"))
                          :command "shift+alt+c"
                          :subsections [:edit]
                          :fn #(st/emit! (dw/copy-link-to-clipboard))}

   :cut                  {:tooltip (ds/meta "X")
                          :command (ds/c-mod "x")
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly
                                (dw/copy-selected)
                                (dw/delete-selected))}

   :paste                {:tooltip (ds/meta "V")
                          :disabled true
                          :command (ds/c-mod "v")
                          :subsections [:edit]
                          :fn (constantly nil)}

   :copy-props           {:tooltip (ds/meta (ds/alt "c"))
                          :command (ds/c-mod "alt+c")
                          :subsections [:edit]
                          :fn #(st/emit! (dw/copy-selected-props))}

   :paste-props          {:tooltip (ds/meta (ds/alt "v"))
                          :command (ds/c-mod "alt+v")
                          :subsections [:edit]
                          :fn #(st/emit! (dw/paste-selected-props))}

   :delete               {:tooltip (ds/supr)
                          :command ["del" "backspace"]
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly (dw/delete-selected))}

   :duplicate            {:tooltip (ds/meta "D")
                          :command (ds/c-mod "d")
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly (dwv/duplicate-or-add-variant))}

   :start-editing        {:tooltip (ds/enter)
                          :command "enter"
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly (dw/start-editing-selected))}

   :start-measure        {:tooltip (ds/alt "")
                          :command ["alt" "."]
                          :type "keydown"
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly (dw/toggle-distances-display true))}

   :stop-measure         {:tooltip (ds/alt "")
                          :command ["alt" "."]
                          :type "keyup"
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly (dw/toggle-distances-display false))}

   :escape               {:tooltip (ds/esc)
                          :command "escape"
                          :subsections [:edit]
                          :fn #(st/emit! esc-pressed)}

   ;; MODIFY LAYERS

   :rename               {:tooltip (ds/alt "N")
                          :command "alt+n"
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly (dw/start-rename-selected))}

   :group                {:tooltip (ds/meta "G")
                          :command (ds/c-mod "g")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/group-selected))}

   :ungroup              {:tooltip (ds/shift "G")
                          :command "shift+g"
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/ungroup-selected))}

   :mask                 {:tooltip (ds/meta "M")
                          :command (ds/c-mod "m")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/mask-group))}

   :unmask               {:tooltip (ds/meta-shift "M")
                          :command (ds/c-mod "shift+m")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/unmask-group))}

   :create-component-variant {:tooltip (ds/meta "K")
                              :command (ds/c-mod "k")
                              :subsections [:modify-layers]
                              :fn #(emit-when-no-readonly (dwv/add-component-or-variant))}

   :detach-component     {:tooltip (ds/meta-shift "K")
                          :command (ds/c-mod "shift+k")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly dwl/detach-selected-components)}

   :flip-vertical        {:tooltip (ds/shift "V")
                          :command "shift+v"
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/flip-vertical-selected))}

   :flip-horizontal      {:tooltip (ds/shift "H")
                          :command "shift+h"
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/flip-horizontal-selected))}
   :bring-forward        {:tooltip (ds/meta ds/up-arrow)
                          :command (ds/c-mod "up")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :up))}

   :bring-backward       {:tooltip (ds/meta ds/down-arrow)
                          :command (ds/c-mod "down")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :down))}

   :bring-front          {:tooltip (ds/meta-shift ds/up-arrow)
                          :command (ds/c-mod "shift+up")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :top))}

   :bring-back           {:tooltip (ds/meta-shift ds/down-arrow)
                          :command (ds/c-mod "shift+down")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :bottom))}

   :move-fast-up         {:tooltip (ds/shift ds/up-arrow)
                          :command ["shift+up" "shift+alt+up"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :up true))}

   :move-fast-down       {:tooltip (ds/shift ds/down-arrow)
                          :command ["shift+down" "shift+alt+down"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :down true))}

   :move-fast-right      {:tooltip (ds/shift ds/right-arrow)
                          :command ["shift+right" "shift+alt+right"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :right true))}

   :move-fast-left       {:tooltip (ds/shift ds/left-arrow)
                          :command ["shift+left" "shift+alt+left"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :left true))}

   :move-unit-up         {:tooltip ds/up-arrow
                          :command ["up" "alt+up"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :up false))}

   :move-unit-down       {:tooltip ds/down-arrow
                          :command ["down" "alt+down"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :down false))}

   :move-unit-left       {:tooltip ds/right-arrow
                          :command ["right" "alt+right"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :right false))}

   :move-unit-right      {:tooltip ds/left-arrow
                          :command ["left" "alt+left"]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :left false))}

   :artboard-selection   {:tooltip (ds/meta (ds/alt "G"))
                          :command (ds/c-mod "alt+g")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dws/create-artboard-from-selection))}

   :toggle-layout-flex   {:tooltip (ds/shift "A")
                          :command "shift+a"
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly
                                (with-meta (dwsl/toggle-layout :flex)
                                  {::ev/origin "workspace:shortcuts"}))}

   :toggle-layout-grid   {:tooltip (ds/meta-shift "A")
                          :command (ds/c-mod "shift+a")
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly
                                (with-meta (dwsl/toggle-layout :grid)
                                  {::ev/origin "workspace:shortcuts"}))}
   ;; TOOLS

   :draw-frame           {:tooltip "B"
                          :command ["b" "a"]
                          :subsections [:tools :basics]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :frame))}

   :move                 {:tooltip "V"
                          :command "v"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly :interrupt)}

   :draw-rect            {:tooltip "R"
                          :command "r"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :rect))}

   :draw-ellipse         {:tooltip "E"
                          :command "e"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :circle))}

   :draw-text            {:tooltip "T"
                          :command "t"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly dwtxt/start-edit-if-selected
                                                      (dwd/select-for-drawing :text))}

   :draw-path            {:tooltip "P"
                          :command "p"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :path))}

   :draw-curve           {:tooltip (ds/shift "C")
                          :command "shift+c"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :curve))}

   :add-comment          {:tooltip "C"
                          :command "c"
                          :subsections [:tools]
                          :fn #(st/emit! (dwd/select-for-drawing :comments))}

   :insert-image         {:tooltip (ds/shift "K")
                          :command "shift+k"
                          :subsections [:tools]
                          :fn #(-> "image-upload" dom/get-element dom/click)}

   :toggle-visibility    {:tooltip (ds/meta-shift "H")
                          :command (ds/c-mod "shift+h")
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dw/toggle-visibility-selected))}

   :toggle-lock          {:tooltip (ds/meta-shift "L")
                          :command (ds/c-mod "shift+l")
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dw/toggle-lock-selected))}

   :toggle-lock-size     {:tooltip (ds/shift "L")
                          :command "shift+l"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dw/toggle-proportion-lock))}

   :scale                {:tooltip "K"
                          :command "k"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (toggle-layout-flag :scale-text))}

   :open-color-picker    {:tooltip "I"
                          :command "i"
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (mdc/picker-for-selected-shape))}

   :toggle-focus-mode    {:command "f"
                          :tooltip "F"
                          :subsections [:basics :tools]
                          :fn #(st/emit! (dw/toggle-focus-mode))}

   ;; ITEM ALIGNMENT

   :align-left           {:tooltip (ds/alt "A")
                          :command "alt+a"
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :hleft))}

   :align-right          {:tooltip (ds/alt "D")
                          :command "alt+d"
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :hright))}

   :align-top            {:tooltip (ds/alt "W")
                          :command "alt+w"
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :vtop))}

   :align-hcenter        {:tooltip (ds/alt "H")
                          :command "alt+h"
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :hcenter))}

   :align-vcenter        {:tooltip (ds/alt "V")
                          :command "alt+v"
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :vcenter))}

   :align-bottom         {:tooltip (ds/alt "S")
                          :command "alt+s"
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :vbottom))}

   :h-distribute         {:tooltip (ds/meta-shift (ds/alt "H"))
                          :command (ds/c-mod "shift+alt+h")
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/distribute-objects :horizontal))}

   :v-distribute         {:tooltip (ds/meta-shift (ds/alt "V"))
                          :command (ds/c-mod "shift+alt+v")
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/distribute-objects :vertical))}

   ;; MAIN MENU

   :toggle-rulers        {:tooltip (ds/meta-shift "R")
                          :command (ds/c-mod "shift+r")
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :rulers))}

   :select-all           {:tooltip (ds/meta "A")
                          :command (ds/c-mod "a")
                          :subsections [:main-menu]
                          :fn #(st/emit! (dw/select-all))}

   :toggle-guides        {:tooltip (ds/meta "'")
                          ;;https://github.com/ccampbell/mousetrap/issues/85
                          :command [(ds/c-mod "'") (ds/c-mod "219")]
                          :show-command (ds/c-mod "'")
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :display-guides))}

   :toggle-alignment     {:tooltip (ds/meta "\\")
                          :command (ds/c-mod "\\")
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :dynamic-alignment))}

   :thumbnail-set        {:tooltip (ds/shift "T")
                          :command "shift+t"
                          :subsections [:main-menu]
                          :fn #(st/emit! (dw/toggle-file-thumbnail-selected))}

   :show-pixel-grid      {:tooltip (ds/shift ",")
                          :command "shift+,"
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :show-pixel-grid))}

   :snap-pixel-grid      {:command ","
                          :tooltip ","
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :snap-pixel-grid))}

   :export-shapes        {:tooltip (ds/meta-shift "E")
                          :command (ds/c-mod "shift+e")
                          :subsections [:basics :main-menu]
                          :fn #(st/emit!
                                (de/show-workspace-export-dialog {:origin "workspace:shortcuts"}))}

   :toggle-snap-ruler-guide {:tooltip (ds/meta-shift "G")
                             :command (ds/c-mod "shift+g")
                             :subsections [:main-menu]
                             :fn #(st/emit! (toggle-layout-flag :snap-ruler-guides))}

   :toggle-snap-guides      {:tooltip (ds/meta-shift "'")
                             ;;https://github.com/ccampbell/mousetrap/issues/85
                             :command [(ds/c-mod "shift+'") (ds/c-mod "shift+219")]
                             :show-command (ds/c-mod "shift+'")
                             :subsections [:main-menu]
                             :fn #(st/emit! (toggle-layout-flag :snap-guides))}

   :show-shortcuts       {:tooltip "?"
                          :command "?"
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :shortcuts))}

   ;; PANELS

   :toggle-layers        {:tooltip (ds/alt "L")
                          :command (ds/a-mod "l")
                          :subsections [:panels]
                          :fn #(st/emit! (dcm/go-to-workspace :layout :layers))}

   :toggle-assets        {:tooltip (ds/alt "I")
                          :command (ds/a-mod "i")
                          :subsections [:panels]
                          :fn #(st/emit! (dcm/go-to-workspace :layout :assets))}

   :toggle-history       {:tooltip (ds/meta-alt "H")
                          :command (ds/ca-mod "h")
                          :subsections [:panels]
                          :fn #(emit-when-no-readonly
                                (dw/toggle-layout-flag :document-history))}

   :toggle-colorpalette  {:tooltip (ds/alt "P")
                          :command (ds/a-mod "p")
                          :subsections [:panels]
                          :fn #(do (r/set-resize-type! :bottom)
                                   (emit-when-no-readonly (dw/remove-layout-flag :hide-palettes)
                                                          (dw/remove-layout-flag :textpalette)
                                                          (toggle-layout-flag :colorpalette)))}

   :toggle-textpalette   {:tooltip (ds/alt "T")
                          :command (ds/a-mod "t")
                          :subsections [:panels]
                          :fn #(do (r/set-resize-type! :bottom)
                                   (emit-when-no-readonly (dw/remove-layout-flag :hide-palettes)
                                                          (dw/remove-layout-flag :colorpalette)
                                                          (toggle-layout-flag :textpalette)))}

   :hide-ui              {:tooltip "\\"
                          :command "\\"
                          :subsections [:panels :basics]
                          :fn #(st/emit! (toggle-layout-flag :hide-ui))}

   ;; ZOOM-WORKSPACE

   :increase-zoom        {:tooltip "+"
                          :command ["+" "="]
                          :subsections [:zoom-workspace]
                          :fn #(st/emit! (dw/increase-zoom))}

   :decrease-zoom        {:tooltip "-"
                          :command ["-" "_"]
                          :subsections [:zoom-workspace]
                          :fn #(st/emit! (dw/decrease-zoom))}

   :reset-zoom           {:tooltip (ds/shift "0")
                          :command "shift+0"
                          :subsections [:zoom-workspace]
                          :fn #(st/emit! dw/reset-zoom)}

   :fit-all              {:tooltip (ds/shift "1")
                          :command "shift+1"
                          :subsections [:zoom-workspace]
                          :fn #(st/emit! dw/zoom-to-fit-all)}

   :zoom-selected        {:tooltip (ds/shift "2")
                          :command ["shift+2" "@" "\""]
                          :subsections [:zoom-workspace]
                          :fn #(st/emit! dw/zoom-to-selected-shape)}

   :zoom-lense-increase  {:tooltip "Z"
                          :command "z"
                          :subsections [:zoom-workspace]
                          :fn identity}

   :zoom-lense-decrease  {:tooltip (ds/alt "Z")
                          :command "alt+z"
                          :subsections [:zoom-workspace]
                          :fn identity}

   ;; NAVIGATION


   :open-viewer          {:tooltip "G V"
                          :command "g v"
                          :subsections [:navigation-workspace]
                          :fn #(st/emit! (dcm/go-to-viewer))}

   :open-inspect         {:tooltip "G I"
                          :command "g i"
                          :subsections [:navigation-workspace]
                          :fn #(st/emit! (dcm/go-to-viewer :section :inspect))}

   :open-comments        {:tooltip "G C"
                          :command "g c"
                          :subsections [:navigation-workspace]
                          :fn #(st/emit! (dcm/go-to-viewer :section :comments))}

   :open-dashboard       {:tooltip "G D"
                          :command "g d"
                          :subsections [:navigation-workspace]
                          :fn #(st/emit! (dcm/go-to-dashboard-recent))}

   :select-prev          {:tooltip (ds/shift "tab")
                          :command "shift+tab"
                          :subsections [:navigation-workspace]
                          :fn #(st/emit! (dw/select-prev-shape))}

   :select-next          {:tooltip ds/tab
                          :command "tab"
                          :subsections [:navigation-workspace]
                          :fn #(st/emit! (dw/select-next-shape))}

   :select-parent-layer  {:tooltip (ds/shift ds/enter)
                          :command "shift+enter"
                          :subsections [:navigation-workspace]
                          :fn #(emit-when-no-readonly (dw/select-parent-layer))}
   ;; SHAPE


   :bool-union           {:tooltip (ds/meta (ds/alt "U"))
                          :command (ds/c-mod "alt+u")
                          :subsections [:shape]
                          :fn #(emit-when-no-readonly (dw/create-bool :union))}

   :bool-difference      {:tooltip (ds/meta (ds/alt "D"))
                          :command (ds/c-mod "alt+d")
                          :subsections [:shape]
                          :fn #(emit-when-no-readonly (dw/create-bool :difference))}

   :bool-intersection    {:tooltip (ds/meta (ds/alt "I"))
                          :command (ds/c-mod "alt+i")
                          :subsections [:shape]
                          :fn #(emit-when-no-readonly (dw/create-bool :intersection))}

   :bool-exclude         {:tooltip (ds/meta (ds/alt "E"))
                          :command (ds/c-mod "alt+e")
                          :subsections [:shape]
                          :fn #(emit-when-no-readonly (dw/create-bool :exclude))}

   ;; THEME
   :toggle-theme         {:tooltip (ds/alt "M")
                          :command (ds/a-mod "m")
                          :subsections [:basics]
                          :fn #(st/emit! (with-meta (du/toggle-theme)
                                           {::ev/origin "workspace:shortcut"}))}


   ;; PLUGINS
   :plugins               {:tooltip (ds/meta (ds/alt "P"))
                           :command (ds/c-mod "alt+p")
                           :subsections [:basics]
                           :fn #(when (features/active-feature? @st/state "plugins/runtime")
                                  (st/emit!
                                   (ptk/event ::ev/event {::ev/name "open-plugins-manager" ::ev/origin "workspace:shortcuts"})
                                   (modal/show :plugin-management {})))}})

(def debug-shortcuts
  ;; PREVIEW
  {:preview-frame        {:tooltip (ds/meta (ds/alt ds/enter))
                          :command (ds/c-mod "alt+enter")
                          :fn #(emit-when-no-readonly (dp/open-preview-selected))}})

(def opacity-shortcuts
  (into {} (->>
            (range 10)
            (map (fn [n] [(keyword (str "opacity-" n))
                          {:tooltip (str n)
                           :command (str n)
                           :subsections [:modify-layers]
                           :fn #(emit-when-no-readonly (dwly/pressed-opacity n))}])))))

(def shortcuts
  (cond-> (merge base-shortcuts opacity-shortcuts dwtxts/shortcuts)
    *assert*
    (merge debug-shortcuts)))

(defn get-tooltip [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (get-in shortcuts [shortcut :tooltip]))

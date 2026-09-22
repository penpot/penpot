;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.shortcuts
  (:require
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.exports.assets :as de]
   [app.main.data.modal :as modal]
   [app.main.data.preview :as dp]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as ds]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.drawing :as dwd]
   [app.main.data.workspace.drawing.common :as dwdc]
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
   [app.util.i18n :refer [tr]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shortcuts
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- toggle-layout-flag
  [flag]
  (-> (dw/toggle-layout-flag flag)
      (vary-meta assoc ::ev/origin "workspace-shortcuts")))

(defn on-display-guides-keydown
  [^js event]
  (let [mod?   (if (cf/check-platform? :macos)
                 (.-metaKey event)
                 (.-ctrlKey event))
        shift? (.-shiftKey event)
        code   (.-code event)]
    (when (and mod?
               (or (and (not shift?) (= "Quote" code))
                   (and shift?       (= "Backslash" code))))
      (.preventDefault event)
      (st/emit! (toggle-layout-flag :display-guides)))))

(defn- emit-when-no-readonly
  [& events]
  (let [can-edit?  (:can-edit (deref refs/permissions))
        read-only? (deref refs/workspace-read-only?)]
    (when (and can-edit? (not read-only?))
      (run! st/emit! events))))

;; Shortcuts format https://github.com/ccampbell/mousetrap

(def base-shortcuts
  {;; EDIT
   :undo                 {:tooltip (ds/meta "Z")
                          :label (fn [] (tr "shortcuts.undo"))
                          :command (ds/c-mod "z")
                          :section [:workspace]
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly dwu/undo)}

   :redo                 {:tooltip (ds/meta "Y")
                          :label (fn [] (tr "shortcuts.redo"))
                          :command [(ds/c-mod "shift+z") (ds/c-mod "y")]
                          :section [:workspace]
                          :subsections [:edit]
                          :fn #(emit-when-no-readonly dwu/redo)}

   :clear-undo           {:tooltip (ds/alt "Q")
                          :label (fn [] (tr "shortcuts.clear-undo"))
                          :command "alt+q"
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly dwu/reinitialize-undo)}

   :copy                 {:tooltip (ds/meta "C")
                          :label (fn [] (tr "shortcuts.copy"))
                          :command (ds/c-mod "c")
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(st/emit! (dw/copy-selected))}

   :copy-link            {:tooltip (ds/shift (ds/alt "C"))
                          :label (fn [] (tr "shortcuts.copy-link"))
                          :command "shift+alt+c"
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(st/emit! (dw/copy-link-to-clipboard))}

   :cut                  {:tooltip (ds/meta "X")
                          :label (fn [] (tr "shortcuts.cut"))
                          :command (ds/c-mod "x")
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly
                                (dw/copy-selected)
                                (dw/delete-selected))}

   :paste                {:tooltip (ds/meta "V")
                          :label (fn [] (tr "shortcuts.paste"))
                          :disabled true
                          :command (ds/c-mod "v")
                          :subsections [:edit]
                          :section [:workspace]
                          :customizable false
                          :fn (constantly nil)}

   :paste-replace        {:tooltip (ds/meta (ds/shift "V"))
                          :label (fn [] (tr "shortcuts.paste-replace"))
                          :command (ds/c-mod "shift+v")
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/paste-from-clipboard {:replace? true}))}

   :copy-props           {:tooltip (ds/meta (ds/alt "c"))
                          :label (fn [] (tr "shortcuts.copy-props"))
                          :command (ds/c-mod "alt+c")
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(st/emit! (dw/copy-selected-props))}

   :paste-props          {:tooltip (ds/meta (ds/alt "v"))
                          :label (fn [] (tr "shortcuts.paste-props"))
                          :command (ds/c-mod "alt+v")
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(st/emit! (dw/paste-selected-props))}

   :delete               {:tooltip (ds/supr)
                          :label (fn [] (tr "shortcuts.delete"))
                          :command ["del" "backspace"]
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/delete-selected))}

   :duplicate            {:tooltip (ds/meta "D")
                          :label (fn [] (tr "shortcuts.duplicate"))
                          :command (ds/c-mod "d")
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwv/duplicate-or-add-variant))}

   :start-editing        {:tooltip (ds/enter)
                          :label (fn [] (tr "shortcuts.start-editing"))
                          :command "enter"
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/start-editing-selected))}

   :show-measure        {:tooltip (ds/alt "")
                         :label (fn [] (tr "shortcuts.show-measure"))
                         :command ["alt" "."]
                         :type ["keydown" "keyup"]
                         :subsections [:tools]
                         :section [:workspace]
                         :fn #(emit-when-no-readonly
                               (let [type (.-type %)]
                                 (dw/toggle-distances-display (if (= type "keydown") true false))))}

   :escape               {:tooltip (ds/esc)
                          :label (fn [] (tr "shortcuts.escape"))
                          :command "escape"
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(st/emit! :interrupt (dwdc/clear-drawing) (dw/deselect-all true))}

   :find             {:tooltip (ds/meta "F") :command (ds/c-mod "f") :subsections [:edit]
                      :label (fn [] (tr "shortcuts.find"))
                      :section [:workspace]
                      :fn #(st/emit! (dw/open-layers-search :find))}
   :find-and-replace {:tooltip (ds/meta "H") :command (ds/c-mod "h") :subsections [:edit]
                      :label (fn [] (tr "shortcuts.find-and-replace"))
                      :section [:workspace]
                      :fn #(st/emit! (dw/open-layers-search :find-and-replace))}

   ;; MODIFY LAYERS

   :rename               {:tooltip (ds/alt "N")
                          :label (fn [] (tr "shortcuts.rename"))
                          :command "alt+n"
                          :subsections [:edit]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/start-rename-selected))}

   :group                {:tooltip (ds/meta "G")
                          :label (fn [] (tr "shortcuts.group"))
                          :command (ds/c-mod "g")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/group-selected))}

   :ungroup              {:tooltip (ds/shift "G")
                          :label (fn [] (tr "shortcuts.ungroup"))
                          :command "shift+g"
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/ungroup-selected))}

   :mask                 {:tooltip (ds/meta "M")
                          :label (fn [] (tr "shortcuts.mask"))
                          :command (ds/c-mod "m")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/mask-group))}

   :unmask               {:tooltip (ds/meta-shift "M")
                          :label (fn [] (tr "shortcuts.unmask"))
                          :command (ds/c-mod "shift+m")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/unmask-group))}

   :create-component-variant {:tooltip (ds/meta "K")
                              :label (fn [] (tr "shortcuts.create-component-variant"))
                              :command (ds/c-mod "k")
                              :subsections [:modify-layers]
                              :section [:workspace]
                              :fn #(emit-when-no-readonly (dwv/add-component-or-variant))}

   :detach-component     {:tooltip (ds/meta-shift "K")
                          :label (fn [] (tr "shortcuts.detach-component"))
                          :command (ds/c-mod "shift+k")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly dwl/detach-selected-components)}

   :flip-vertical        {:tooltip (ds/shift "V")
                          :label (fn [] (tr "shortcuts.flip-vertical"))
                          :command "shift+v"
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/flip-vertical-selected))}

   :flip-horizontal      {:tooltip (ds/shift "H")
                          :label (fn [] (tr "shortcuts.flip-horizontal"))
                          :command "shift+h"
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/flip-horizontal-selected))}
   :bring-forward        {:tooltip (ds/meta ds/up-arrow)
                          :label (fn [] (tr "shortcuts.bring-forward"))
                          :command (ds/c-mod "up")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :up))}

   :bring-backward       {:tooltip (ds/meta ds/down-arrow)
                          :label (fn [] (tr "shortcuts.bring-backward"))
                          :command (ds/c-mod "down")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :down))}

   :bring-front          {:tooltip (ds/meta-shift ds/up-arrow)
                          :label (fn [] (tr "shortcuts.bring-front"))
                          :command (ds/c-mod "shift+up")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :top))}

   :bring-back           {:tooltip (ds/meta-shift ds/down-arrow)
                          :label (fn [] (tr "shortcuts.bring-back"))
                          :command (ds/c-mod "shift+down")
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/vertical-order-selected :bottom))}

   :move-fast-up         {:tooltip (ds/shift ds/up-arrow)
                          :label (fn [] (tr "shortcuts.move-fast-up"))
                          :command ["shift+up" "shift+alt+up"]
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwt/move-selected :up true))}

   :move-fast-down       {:tooltip (ds/shift ds/down-arrow)
                          :label (fn [] (tr "shortcuts.move-fast-down"))
                          :command ["shift+down" "shift+alt+down"]
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwt/move-selected :down true))}

   :move-fast-right      {:tooltip (ds/shift ds/right-arrow)
                          :label (fn [] (tr "shortcuts.move-fast-right"))
                          :command ["shift+right" "shift+alt+right"]
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwt/move-selected :right true))}

   :move-fast-left       {:tooltip (ds/shift ds/left-arrow)
                          :label (fn [] (tr "shortcuts.move-fast-left"))
                          :command ["shift+left" "shift+alt+left"]
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwt/move-selected :left true))}

   :move-unit-up         {:tooltip ds/up-arrow
                          :label (fn [] (tr "shortcuts.move-unit-up"))
                          :command ["up" "alt+up"]
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwt/move-selected :up false))}

   :move-unit-down       {:tooltip ds/down-arrow
                          :label (fn [] (tr "shortcuts.move-unit-down"))
                          :command ["down" "alt+down"]
                          :section [:workspace]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :down false))}

   :move-unit-left       {:tooltip ds/right-arrow
                          :label (fn [] (tr "shortcuts.move-unit-left"))
                          :command ["right" "alt+right"]
                          :subsections [:modify-layers]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwt/move-selected :right false))}

   :move-unit-right      {:tooltip ds/left-arrow
                          :label (fn [] (tr "shortcuts.move-unit-right"))
                          :command ["left" "alt+left"]
                          :section [:workspace]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dwt/move-selected :left false))}

   :artboard-selection   {:tooltip (ds/meta (ds/alt "G"))
                          :label (fn [] (tr "shortcuts.artboard-selection"))
                          :command (ds/c-mod "alt+g")
                          :section [:workspace]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly (dws/create-artboard-from-selection))}

   :toggle-layout-flex   {:tooltip (ds/shift "A")
                          :label (fn [] (tr "shortcuts.toggle-layout-flex"))
                          :command "shift+a"
                          :section [:workspace]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly
                                (with-meta (dwsl/toggle-layout :flex)
                                  {::ev/origin "workspace:shortcuts"}))}

   :toggle-layout-grid   {:tooltip (ds/meta-shift "A")
                          :label (fn [] (tr "shortcuts.toggle-layout-grid"))
                          :command (ds/c-mod "shift+a")
                          :section [:workspace]
                          :subsections [:modify-layers]
                          :fn #(emit-when-no-readonly
                                (with-meta (dwsl/toggle-layout :grid)
                                  {::ev/origin "workspace:shortcuts"}))}
   ;; TOOLS

   :draw-frame           {:tooltip "B"
                          :label (fn [] (tr "shortcuts.draw-frame"))
                          :command ["b" "a"]
                          :section [:workspace :basics]
                          :subsections [:tools :basics]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :frame))}

   :move                 {:tooltip "V"
                          :label (fn [] (tr "shortcuts.move"))
                          :command "v"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly :interrupt)}

   :draw-rect            {:tooltip "R"
                          :label (fn [] (tr "shortcuts.draw-rect"))
                          :command "r"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :rect))}

   :draw-ellipse         {:tooltip "E"
                          :label (fn [] (tr "shortcuts.draw-ellipse"))
                          :command "e"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :circle))}

   :draw-text            {:tooltip "T"
                          :label (fn [] (tr "shortcuts.draw-text"))
                          :command "t"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly dwtxt/start-edit-if-selected
                                                      (dwd/select-for-drawing :text))}

   :draw-path            {:tooltip "P"
                          :label (fn [] (tr "shortcuts.draw-path"))
                          :command "p"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :path))}

   :draw-line            {:tooltip "L"
                          :label (fn [] (tr "shortcuts.draw-line"))
                          :command "l"
                          :subsections [:tools]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :line))}

   :draw-arrow           {:tooltip (ds/shift "L")
                          :label (fn [] (tr "shortcuts.draw-arrow"))
                          :command "shift+l"
                          :subsections [:tools]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :arrow))}

   :draw-curve           {:tooltip (ds/shift "C")
                          :label (fn [] (tr "shortcuts.draw-curve"))
                          :command "shift+c"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dwd/select-for-drawing :curve))}

   :add-comment          {:tooltip "C"
                          :label (fn [] (tr "shortcuts.add-comment"))
                          :command "c"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(st/emit! (dwd/select-for-drawing :comments))}

   :toggle-comments-visibility
   {:tooltip (ds/meta-shift "C")
    :label (fn [] (tr "shortcuts.toggle-comments-visibility"))
    :command (ds/c-mod "shift+c")
    :section [:workspace]
    :subsections [:main-menu]
    :fn #(st/emit! (dwcm/toggle-comments-visibility {:origin "workspace-shortcuts"}))}

   :insert-image         {:tooltip (ds/shift "K")
                          :label (fn [] (tr "shortcuts.insert-image"))
                          :command "shift+k"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(-> "image-upload" dom/get-element dom/click)}

   :toggle-visibility    {:tooltip (ds/meta-shift "H")
                          :label (fn [] (tr "shortcuts.toggle-visibility"))
                          :command (ds/c-mod "shift+h")
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dw/toggle-visibility-selected))}

   :toggle-lock          {:tooltip (ds/meta-shift "L")
                          :label (fn [] (tr "shortcuts.toggle-lock"))
                          :command (ds/c-mod "shift+l")
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dw/toggle-lock-selected))}

   :toggle-lock-size     {:tooltip (ds/shift "L")
                          :label (fn [] (tr "shortcuts.toggle-lock-size"))
                          :command "shift+l"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (dw/toggle-proportion-lock))}

   :scale                {:tooltip "K"
                          :label (fn [] (tr "shortcuts.scale"))
                          :command "k"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (toggle-layout-flag :scale-text))}

   :open-color-picker    {:tooltip "I"
                          :label (fn [] (tr "shortcuts.open-color-picker"))
                          :command "i"
                          :section [:workspace]
                          :subsections [:tools]
                          :fn #(emit-when-no-readonly (mdc/picker-for-selected-shape))}

   :toggle-focus-mode    {:command "f"
                          :label (fn [] (tr "shortcuts.toggle-focus-mode"))
                          :tooltip "F"
                          :section [:workspace :basics]
                          :subsections [:basics :tools]
                          :fn #(st/emit! (dw/toggle-focus-mode))}

   ;; ITEM ALIGNMENT

   :align-left           {:tooltip (ds/alt "A")
                          :label (fn [] (tr "shortcuts.align-left"))
                          :command "alt+a"
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :hleft))}

   :align-right          {:tooltip (ds/alt "D")
                          :label (fn [] (tr "shortcuts.align-right"))
                          :command "alt+d"
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :hright))}

   :align-top            {:tooltip (ds/alt "W")
                          :label (fn [] (tr "shortcuts.align-top"))
                          :command "alt+w"
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :vtop))}

   :align-hcenter        {:tooltip (ds/alt "H")
                          :label (fn [] (tr "shortcuts.align-hcenter"))
                          :command "alt+h"
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :hcenter))}

   :align-vcenter        {:tooltip (ds/alt "V")
                          :label (fn [] (tr "shortcuts.align-vcenter"))
                          :command "alt+v"
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :vcenter))}

   :align-bottom         {:tooltip (ds/alt "S")
                          :label (fn [] (tr "shortcuts.align-bottom"))
                          :command "alt+s"
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/align-objects :vbottom))}

   :h-distribute         {:tooltip (ds/meta-shift (ds/alt "H"))
                          :label (fn [] (tr "shortcuts.h-distribute"))
                          :command (ds/c-mod "shift+alt+h")
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/distribute-objects :horizontal))}

   :v-distribute         {:tooltip (ds/meta-shift (ds/alt "V"))
                          :label (fn [] (tr "shortcuts.v-distribute"))
                          :command (ds/c-mod "shift+alt+v")
                          :section [:workspace]
                          :subsections [:alignment]
                          :fn #(emit-when-no-readonly (dw/distribute-objects :vertical))}

   ;; MAIN MENU

   :toggle-rulers        {:tooltip (ds/meta-shift "R")
                          :label (fn [] (tr "shortcuts.toggle-rulers"))
                          :command (ds/c-mod "shift+r")
                          :section [:workspace]
                          :subsections [:main-menu]
                          :fn #(st/emit! (toggle-layout-flag :rulers))}

   :select-all           {:tooltip (ds/meta "A")
                          :label (fn [] (tr "shortcuts.select-all"))
                          :command (ds/c-mod "a")
                          :section [:workspace]
                          :subsections [:main-menu]
                          :fn #(st/emit! (dw/select-all))}

   :toggle-guides        {:tooltip (ds/meta "'")
                          :label (fn [] (tr "shortcuts.toggle-guides"))
                          ;;https://github.com/ccampbell/mousetrap/issues/85
                          :command [(ds/c-mod "'") (ds/c-mod "219")]
                          :show-command (ds/c-mod "'")
                          :subsections [:main-menu]
                          :section [:workspace]
                          :fn #(st/emit! (toggle-layout-flag :display-guides))}

   :toggle-alignment     {:tooltip (ds/meta "\\")
                          :label (fn [] (tr "shortcuts.toggle-alignment"))
                          :command (ds/c-mod "\\")
                          :subsections [:main-menu]
                          :section [:workspace]
                          :fn #(st/emit! (toggle-layout-flag :dynamic-alignment))}

   :thumbnail-set        {:tooltip (ds/shift "T")
                          :label (fn [] (tr "shortcuts.thumbnail-set"))
                          :command "shift+t"
                          :subsections [:main-menu]
                          :section [:workspace]
                          :fn #(st/emit! (dw/toggle-file-thumbnail-selected))}

   :show-pixel-grid      {:tooltip (ds/shift ",")
                          :label (fn [] (tr "shortcuts.show-pixel-grid"))
                          :command "shift+,"
                          :subsections [:main-menu]
                          :section [:workspace]
                          :fn #(st/emit! (toggle-layout-flag :show-pixel-grid))}

   :snap-pixel-grid      {:command ","
                          :label (fn [] (tr "shortcuts.snap-pixel-grid"))
                          :tooltip ","
                          :subsections [:main-menu]
                          :section [:workspace]
                          :fn #(st/emit! (toggle-layout-flag :snap-pixel-grid))}

   :export-shapes        {:tooltip (ds/meta-shift "E")
                          :label (fn [] (tr "shortcuts.export-shapes"))
                          :command (ds/c-mod "shift+e")
                          :subsections [:basics :main-menu]
                          :section [:workspace :basics]
                          :fn #(st/emit!
                                (de/show-workspace-export-dialog {:origin "workspace:shortcuts"}))}

   :toggle-snap-ruler-guide {:tooltip (ds/meta-shift "G")
                             :label (fn [] (tr "shortcuts.toggle-snap-ruler-guide"))
                             :command (ds/c-mod "shift+g")
                             :subsections [:main-menu]
                             :section [:workspace]
                             :fn #(st/emit! (toggle-layout-flag :snap-ruler-guides))}

   :toggle-snap-guides      {:tooltip (ds/meta-shift "'")
                             :label (fn [] (tr "shortcuts.toggle-snap-guides"))
                             ;;https://github.com/ccampbell/mousetrap/issues/85
                             :command [(ds/c-mod "shift+'") (ds/c-mod "shift+219")]
                             :show-command (ds/c-mod "shift+'")
                             :subsections [:main-menu]
                             :section [:workspace]
                             :fn #(st/emit! (toggle-layout-flag :snap-guides))}

   :show-shortcuts       {:tooltip "?"
                          :label (fn [] (tr "shortcuts.show-shortcuts"))
                          :command "?"
                          :subsections [:main-menu]
                          :section [:workspace]
                          :fn #(st/emit! (toggle-layout-flag :shortcuts))}

   ;; PANELS

   :toggle-layers        {:tooltip (ds/alt "L")
                          :label (fn [] (tr "shortcuts.toggle-layers"))
                          :command (ds/a-mod "l")
                          :subsections [:panels]
                          :section [:workspace]
                          :fn #(st/emit! (dcm/go-to-workspace :layout :layers))}

   :toggle-assets        {:tooltip (ds/alt "I")
                          :label (fn [] (tr "shortcuts.toggle-assets"))
                          :command (ds/a-mod "i")
                          :subsections [:panels]
                          :section [:workspace]
                          :fn #(st/emit! (dcm/go-to-workspace :layout :assets))}

   :toggle-history       {:tooltip (ds/meta-alt "H")
                          :label (fn [] (tr "shortcuts.toggle-history"))
                          :command (ds/ca-mod "h")
                          :subsections [:panels]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly
                                (dw/toggle-layout-flag :document-history))}

   :toggle-colorpalette  {:tooltip (ds/alt "P")
                          :label (fn [] (tr "shortcuts.toggle-colorpalette"))
                          :command (ds/a-mod "p")
                          :subsections [:panels]
                          :section [:workspace]
                          :fn #(do (r/set-resize-type! :bottom)
                                   (emit-when-no-readonly (dw/remove-layout-flag :hide-palettes)
                                                          (dw/remove-layout-flag :textpalette)
                                                          (toggle-layout-flag :colorpalette)))}

   :toggle-textpalette   {:tooltip (ds/alt "T")
                          :label (fn [] (tr "shortcuts.toggle-textpalette"))
                          :command (ds/a-mod "t")
                          :subsections [:panels]
                          :section [:workspace]
                          :fn #(do (r/set-resize-type! :bottom)
                                   (emit-when-no-readonly (dw/remove-layout-flag :hide-palettes)
                                                          (dw/remove-layout-flag :colorpalette)
                                                          (toggle-layout-flag :textpalette)))}

   :hide-ui              {:tooltip "\\"
                          :label (fn [] (tr "shortcuts.hide-ui"))
                          :command "\\"
                          :subsections [:panels :basics]
                          :section [:workspace :basics]
                          :fn #(st/emit! (toggle-layout-flag :hide-ui))}

   ;; ZOOM-WORKSPACE

   :increase-zoom        {:tooltip "+"
                          :label (fn [] (tr "shortcuts.increase-zoom"))
                          :command ["+" "="]
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dw/increase-zoom))}

   :decrease-zoom        {:tooltip "-"
                          :label (fn [] (tr "shortcuts.decrease-zoom"))
                          :command ["-" "_"]
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dw/decrease-zoom))}

   :reset-zoom           {:tooltip (ds/shift "0")
                          :label (fn [] (tr "shortcuts.reset-zoom"))
                          :command ["shift+0" "shift+num0"]
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn #(st/emit! dw/reset-zoom)}

   :fit-all              {:tooltip (ds/shift "1")
                          :label (fn [] (tr "shortcuts.fit-all"))
                          :command ["shift+1" "shift+num1"]
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn #(st/emit! dw/zoom-to-fit-all)}

   :zoom-selected        {:tooltip (ds/shift "2")
                          :label (fn [] (tr "shortcuts.zoom-selected"))
                          :command ["shift+2" "shift+num2" "@" "\""]
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn #(st/emit! dw/zoom-to-selected-shape)}

   :zoom-lense-increase  {:tooltip "Z"
                          :label (fn [] (tr "shortcuts.zoom-lense-increase"))
                          :command "z"
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn identity}

   :zoom-lense-decrease  {:tooltip (ds/alt "Z")
                          :label (fn [] (tr "shortcuts.zoom-lense-decrease"))
                          :command "alt+z"
                          :subsections [:zoom-workspace]
                          :section [:workspace]
                          :fn identity}

   ;; NAVIGATION


   :open-viewer          {:tooltip "G V"
                          :label (fn [] (tr "shortcuts.open-viewer"))
                          :command "g v"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dcm/go-to-viewer))}

   :open-inspect         {:tooltip "G I"
                          :label (fn [] (tr "shortcuts.open-inspect"))
                          :command "g i"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dcm/go-to-viewer :section :inspect))}

   :open-comments        {:tooltip "G C"
                          :label (fn [] (tr "shortcuts.open-comments"))
                          :command "g c"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dcm/go-to-viewer :section :comments))}

   :open-dashboard       {:tooltip "G D"
                          :label (fn [] (tr "shortcuts.open-dashboard"))
                          :command "g d"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dcm/go-to-dashboard-recent))}

   :select-prev          {:tooltip (ds/shift "tab")
                          :label (fn [] (tr "shortcuts.select-prev"))
                          :command "shift+tab"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dw/select-prev-shape))}

   :select-next          {:tooltip ds/tab
                          :label (fn [] (tr "shortcuts.select-next"))
                          :command "tab"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(st/emit! (dw/select-next-shape))}

   :select-parent-layer  {:tooltip (ds/shift ds/enter)
                          :label (fn [] (tr "shortcuts.select-parent-layer"))
                          :command "shift+enter"
                          :subsections [:navigation-workspace]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/select-parent-layer))}
   ;; SHAPE


   :bool-union           {:tooltip (ds/meta (ds/alt "U"))
                          :label (fn [] (tr "shortcuts.bool-union"))
                          :command (ds/c-mod "alt+u")
                          :subsections [:shape]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/create-bool :union))}

   :bool-difference      {:tooltip (ds/meta (ds/alt "D"))
                          :label (fn [] (tr "shortcuts.bool-difference"))
                          :command (ds/c-mod "alt+d")
                          :subsections [:shape]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/create-bool :difference))}

   :bool-intersection    {:tooltip (ds/meta (ds/alt "I"))
                          :label (fn [] (tr "shortcuts.bool-intersection"))
                          :command (ds/c-mod "alt+i")
                          :subsections [:shape]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/create-bool :intersection))}

   :bool-exclude         {:tooltip (ds/meta (ds/alt "E"))
                          :label (fn [] (tr "shortcuts.bool-exclude"))
                          :command (ds/c-mod "alt+e")
                          :subsections [:shape]
                          :section [:workspace]
                          :fn #(emit-when-no-readonly (dw/create-bool :exclude))}

   ;; THEME
   :toggle-theme         {:tooltip (ds/alt "M")
                          :label (fn [] (tr "shortcuts.toggle-theme"))
                          :command (ds/a-mod "m")
                          :subsections [:generic :basics]
                          :section [:workspace :basics]
                          :fn #(st/emit! (with-meta (du/toggle-theme)
                                           {::ev/origin "workspace:shortcut"}))}


   ;; PLUGINS
   :plugins               {:tooltip (ds/meta (ds/alt "P"))
                           :label (fn [] (tr "shortcuts.plugins"))
                           :command (ds/c-mod "alt+p")
                           :subsections [:generic :basics]
                           :section [:workspace :basics]
                           :fn #(when (features/active-feature? @st/state "plugins/runtime")
                                  (st/emit!
                                   (ev/event {::ev/name "open-plugins-manager" ::ev/origin "workspace:shortcuts"})
                                   (modal/show :plugin-management {})))}})

(def debug-shortcuts
  ;; PREVIEW
  {:preview-frame        {:tooltip (ds/meta (ds/alt ds/enter))
                          :command (ds/c-mod "alt+enter")
                          :fn #(emit-when-no-readonly (dp/open-preview-selected))}})

(def opacity-shortcuts
  {:opacity-0 {:label (fn [] (tr "shortcuts.opacity-0"))
               :tooltip "0"
               :command ["0" "num0"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 0))}
   :opacity-1 {:label (fn [] (tr "shortcuts.opacity-1"))
               :tooltip "1"
               :command ["1" "num1"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 1))}
   :opacity-2 {:label (fn [] (tr "shortcuts.opacity-2"))
               :tooltip "2"
               :command ["2" "num2"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 2))}
   :opacity-3 {:label (fn [] (tr "shortcuts.opacity-3"))
               :tooltip "3"
               :command ["3" "num3"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 3))}
   :opacity-4 {:label (fn [] (tr "shortcuts.opacity-4"))
               :tooltip "4"
               :command ["4" "num4"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 4))}
   :opacity-5 {:label (fn [] (tr "shortcuts.opacity-5"))
               :tooltip "5"
               :command ["5" "num5"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 5))}
   :opacity-6 {:label (fn [] (tr "shortcuts.opacity-6"))
               :tooltip "6"
               :command ["6" "num6"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 6))}
   :opacity-7 {:label (fn [] (tr "shortcuts.opacity-7"))
               :tooltip "7"
               :command ["7" "num7"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 7))}
   :opacity-8 {:label (fn [] (tr "shortcuts.opacity-8"))
               :tooltip "8"
               :command ["8" "num8"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 8))}
   :opacity-9 {:label (fn [] (tr "shortcuts.opacity-9"))
               :tooltip "9"
               :command ["9" "num9"]
               :section [:workspace]
               :subsections [:modify-layers]
               :fn #(emit-when-no-readonly (dwly/pressed-opacity 9))}})

(def shortcuts
  (cond-> (merge base-shortcuts opacity-shortcuts dwtxts/shortcuts)
    *assert*
    (merge debug-shortcuts)))

(defn get-tooltip
  "Returns the tooltip string for a shortcut, using any custom binding
  from the user's profile props if one exists, falling back to the
  default :tooltip field."
  [shortcut]
  (assert (contains? shortcuts shortcut) (str shortcut))
  (let [custom-shortcuts (deref refs/custom-shortcuts)
        custom-command   (get-in custom-shortcuts [:workspace shortcut])]
    (if (and custom-command (not= custom-command ""))
      (ds/command->tooltip custom-command)
      (get-in shortcuts [shortcut :tooltip]))))

(defn get-effective-tooltip
  "Returns the tooltip string for a shortcut given an already-resolved
  custom-shortcuts map. Use this when you already have the custom
  shortcuts derefed for the current render cycle."
  [shortcut custom-shortcuts]
  (let [custom-command (get-in custom-shortcuts [:workspace shortcut])]
    (if (and custom-command (not= custom-command ""))
      (ds/command->tooltip custom-command)
      (get-in shortcuts [shortcut :tooltip]))))

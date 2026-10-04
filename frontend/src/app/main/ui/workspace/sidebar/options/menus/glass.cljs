;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.glass
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- create-glass
  "Defaults from the Clear glass of Apple's iOS 26 design kit."
  []
  {:id (uuid/next)
   :hidden false
   :refraction 0.7
   :depth 30
   :dispersion 0.2
   :frost 6
   :splay 0.2
   :light-intensity 0.4
   :light-angle 0})

(mf/defc glass-field*
  [{:keys [glass attr label text-icon percent max on-change]}]
  (let [value (get glass attr)
        handle-change
        (mf/use-fn
         (mf/deps attr percent on-change)
         (fn [value]
           (on-change attr (if percent (/ value 100) value))))]
    [:> numeric-input*
     {:class (stl/css :numeric-input)
      :placeholder "--"
      :min (if (= attr :light-angle) -360 0)
      :max max
      :text-icon text-icon
      :property label
      :on-change handle-change
      :value (if percent (mth/round (* value 100)) value)}]))

(mf/defc glass-menu*
  [{:keys [ids value]}]
  (let [enabled? (and (features/use-feature "render-wasm/v1")
                      (contains? cf/flags :liquid-glass))
        hidden?  (get value :hidden)

        change!
        (mf/use-fn
         (mf/deps ids)
         (fn [update-fn]
           (st/emit! (dwsh/update-shapes ids update-fn)
                     (udw/trigger-bounding-box-cloaking ids))))

        handle-add
        (mf/use-fn (mf/deps change!) #(change! (fn [shape] (assoc shape :glass (create-glass)))))

        handle-remove
        (mf/use-fn (mf/deps change!) #(change! (fn [shape] (dissoc shape :glass))))

        handle-toggle
        (mf/use-fn (mf/deps change!) #(change! (fn [shape] (update-in shape [:glass :hidden] not))))

        handle-field
        (mf/use-fn
         (mf/deps change!)
         (fn [attr value]
           (change! (fn [shape] (assoc-in shape [:glass attr] value)))))]

    (when enabled?
      [:section {:class (stl/css :element-set)
                 :aria-label (tr "workspace.options.glass-options.title")}
       [:div {:class (stl/css :element-title)}
        [:> title-bar* {:collapsable false
                        :title (tr "workspace.options.glass-options.title")
                        :class (stl/css :title-spacing)}
         (when (nil? value)
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.glass-options.add")
                             :on-click handle-add
                             :icon i/add
                             :tooltip-placement "top-left"}])]]

       (when (some? value)
         [:div {:class (stl/css :element-set-content)}
          [:div {:class (stl/css-case :first-row true :hidden hidden?)}
           [:span {:class (stl/css :label)} (tr "workspace.options.glass-options.title")]
           [:div {:class (stl/css :actions)}
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "workspace.options.glass-options.toggle")
                              :on-click handle-toggle
                              :tooltip-placement "top-left"
                              :icon (if hidden? i/hide i/shown)}]
            [:> icon-button* {:variant "ghost"
                              :aria-label (tr "workspace.options.glass-options.remove")
                              :on-click handle-remove
                              :tooltip-placement "top-left"
                              :icon i/remove}]]]

          (when-not hidden?
            [:div {:class (stl/css :fields)}
             [:> glass-field* {:glass value :attr :refraction :percent true :max 100
                               :text-icon "REFR" :label (tr "workspace.options.glass-options.refraction")
                               :on-change handle-field}]
             [:> glass-field* {:glass value :attr :depth
                               :text-icon "DEPTH" :label (tr "workspace.options.glass-options.depth")
                               :on-change handle-field}]
             [:> glass-field* {:glass value :attr :dispersion :percent true :max 100
                               :text-icon "DISP" :label (tr "workspace.options.glass-options.dispersion")
                               :on-change handle-field}]
             [:> glass-field* {:glass value :attr :frost
                               :text-icon "FROST" :label (tr "workspace.options.glass-options.frost")
                               :on-change handle-field}]
             [:> glass-field* {:glass value :attr :light-intensity :percent true :max 100
                               :text-icon "LIGHT" :label (tr "workspace.options.glass-options.light")
                               :on-change handle-field}]
             [:> glass-field* {:glass value :attr :light-angle :max 360
                               :text-icon "ANGLE" :label (tr "workspace.options.glass-options.light-angle")
                               :on-change handle-field}]
             [:> glass-field* {:glass value :attr :splay :percent true :max 100
                               :text-icon "SPLAY" :label (tr "workspace.options.glass-options.splay")
                               :on-change handle-field}]])])])))

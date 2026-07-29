;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.stroke
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.stroke :as cts]
   [app.config :as cf]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.rows.stroke-row :refer [stroke-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def stroke-attrs
  [:strokes
   :stroke-style
   :stroke-alignment
   :stroke-width
   :stroke-dash
   :stroke-gap
   :stroke-color
   :stroke-color-ref-id
   :stroke-color-ref-file
   :stroke-opacity
   :stroke-color-gradient
   :stroke-cap-start
   :stroke-cap-end])

(defn- stroke-menu-check-props
  "A stroke-menu specific memoize check function that only checks if
  specific values are changed on provided props. This allows pass the
  whole shape as values without adding additional rerenders when other
  shape properties changes."
  [n-props o-props]
  (and (identical? (unchecked-get n-props "ids")
                   (unchecked-get o-props "ids"))
       (identical? (unchecked-get n-props "type")
                   (unchecked-get o-props "type"))
       (identical? (unchecked-get n-props "appliedTokens")
                   (unchecked-get o-props "appliedTokens"))
       (identical? (unchecked-get n-props "showCaps")
                   (unchecked-get o-props "showCaps"))
       (identical? (unchecked-get n-props "disableStrokeStyle")
                   (unchecked-get o-props "disableStrokeStyle"))
       (let [o-vals  (unchecked-get o-props "values")
             n-vals  (unchecked-get n-props "values")
             o-strokes (get o-vals :strokes)
             n-strokes (get n-vals :strokes)]
         (identical? o-strokes n-strokes))))

(mf/defc stroke-menu*
  {::mf/wrap [#(mf/memo' % stroke-menu-check-props)]}
  [{:keys [ids type values show-caps disable-stroke-style applied-tokens]}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-stroke")
                :group (tr "workspace.options.group-stroke")
                (tr "labels.stroke"))

        state*          (mf/use-state true)
        open?           (deref state*)

        toggle-content  (mf/use-fn #(swap! state* not))
        open-content    (mf/use-fn #(reset! state* true))

        strokes         (:strokes values)
        has-strokes?    (or (= :multiple strokes) (some? (seq strokes)))


        on-color-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index color]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/change-stroke-color ids color index))))


        on-remove
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/remove-stroke ids index))))

        handle-remove-all
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/remove-all-strokes ids))))

        on-color-detach
        (mf/use-fn
         (mf/deps ids)
         (fn [index color]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (let [color (-> color
                           (dissoc :ref-id :ref-file))]
             (st/emit! (dc/change-stroke-color ids color index)))))

        handle-reorder
        (mf/use-fn
         (mf/deps ids)
         (fn [from-pos to-space-between-pos]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/reorder-strokes ids from-pos to-space-between-pos))))

        on-stroke-style-change
        (mf/use-fn
         (mf/deps ids)
         (fn [index value]
           (st/emit! (udw/trigger-bounding-box-cloaking ids))
           (st/emit! (dc/change-stroke-attrs ids {:stroke-style value} index))))

        on-stroke-alignment-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-alignment value} index))))

        on-stroke-width-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-width value} index))))

        wasm-render?
        (features/use-feature "render-wasm/v1")

        per-side-available?
        (and (contains? cf/flags :stroke-per-side)
             (or (= type :rect) (= type :frame)))

        per-side-disabled?
        (not wasm-render?)

        on-stroke-per-side-toggle
        (fn [index]
          (let [stroke  (get-in values [:strokes index])
                active? (:stroke-per-side stroke)
                width   (:stroke-width stroke)
                width   (if (number? width) width 1)]
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (if active?
              (st/emit! (dc/change-stroke-attrs ids {:stroke-per-side false} index))
              ;; Entering per-side mode seeds any missing side from the
              ;; uniform width, so previous per-side edits are preserved.
              ;; The top value doubles as the global :stroke-width.
              (let [top (d/nilv (:stroke-width-top stroke) width)]
                (st/emit! (dc/change-stroke-attrs
                           ids
                           {:stroke-per-side true
                            :stroke-width top
                            :stroke-width-top top
                            :stroke-width-right (d/nilv (:stroke-width-right stroke) width)
                            :stroke-width-bottom (d/nilv (:stroke-width-bottom stroke) width)
                            :stroke-width-left (d/nilv (:stroke-width-left stroke) width)}
                           index))))))

        on-stroke-width-side-change
        (fn [index attr value]
          (when (number? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            ;; Code paths that don't know about per-side data (old render,
            ;; global width input, exports) keep reading :stroke-width, and
            ;; per spec they must see the TOP side there. So editing the top
            ;; side also writes :stroke-width; the other sides only write
            ;; their own attr.
            (let [attrs (cond-> {attr value}
                          (= attr :stroke-width-top)
                          (assoc :stroke-width value))]
              (st/emit! (dc/change-stroke-attrs ids attrs index)))))

        on-stroke-dash-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-dash value} index))))

        on-stroke-gap-change
        (fn [index value]
          (when-not (str/empty? value)
            (st/emit! (udw/trigger-bounding-box-cloaking ids))
            (st/emit! (dc/change-stroke-attrs ids {:stroke-gap value} index))))

        on-stroke-cap-start-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:stroke-cap-start value} index)))

        on-stroke-cap-end-change
        (fn [index value]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/change-stroke-attrs ids {:stroke-cap-end value} index)))

        on-stroke-cap-switch
        (fn [index]
          (let [stroke-cap-start (get-in values [:strokes index :stroke-cap-start])
                stroke-cap-end   (get-in values [:strokes index :stroke-cap-end])]
            (when (and (not= stroke-cap-start :multiple)
                       (not= stroke-cap-end :multiple))
              (st/emit! (udw/trigger-bounding-box-cloaking ids))
              (st/emit! (dc/change-stroke-attrs ids {:stroke-cap-start stroke-cap-end
                                                     :stroke-cap-end stroke-cap-start} index)))))
        on-toggle-visibility
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (st/emit! (udw/trigger-bounding-box-cloaking ids)
                     (dwsh/update-shapes ids #(update-in % [:strokes index :hidden] not)))))

        on-add-stroke
        (fn [_]
          (st/emit! (udw/trigger-bounding-box-cloaking ids))
          (st/emit! (dc/add-stroke ids cts/default-stroke))
          (when (not (some? (seq strokes))) (open-content)))

        disable-drag    (mf/use-state false)

        on-focus (fn [_]
                   (reset! disable-drag true))

        on-blur (fn [_]
                  (reset! disable-drag false))

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token-name attrs]
           (st/emit! (dwta/unapply-token {:token-name token-name
                                          :attributes attrs
                                          :shape-ids ids}))))]

    [:section {:class (stl/css :stroke-section)
               :aria-label "Stroke section"}
     [:div {:class (stl/css :stroke-title)}
      [:> title-bar* {:collapsable  has-strokes?
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :title        label
                      :class        (stl/css-case :stroke-title-bar (not has-strokes?))}
       (when (not (= :multiple strokes))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.stroke.add-stroke")
                           :on-click on-add-stroke
                           :icon i/add
                           :data-testid "add-stroke"}])]]
     (when open?
       [:div {:class (stl/css-case :stroke-content true
                                   :stroke-content-empty (not has-strokes?))}
        (cond
          (or (= :multiple (:stroke-color applied-tokens))
              (= :multiple (:stroke-width applied-tokens))
              (= :multiple strokes))
          [:div {:class (stl/css :stroke-multiple)}
           [:div {:class (stl/css :stroke-multiple-label)}
            (tr "settings.multiple")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.stroke.remove-stroke")
                             :on-click handle-remove-all
                             :icon i/remove}]]
          (seq strokes)
          [:> h/sortable-container* {}
           (for [[index value] (d/enumerate (:strokes values []))]
             [:> stroke-row* {:key (dm/str "stroke-" index "-" (hash applied-tokens))
                              :index index
                              :stroke value
                              :title (tr "workspace.options.stroke-color")
                              :show-caps show-caps
                              :on-color-change on-color-change
                              :on-reorder handle-reorder
                              :on-color-detach on-color-detach
                              :on-remove on-remove
                              :on-stroke-width-change on-stroke-width-change
                              :per-side-available per-side-available?
                              :per-side-disabled per-side-disabled?
                              :on-stroke-per-side-toggle on-stroke-per-side-toggle
                              :on-stroke-width-side-change on-stroke-width-side-change
                              :on-stroke-dash-change on-stroke-dash-change
                              :on-stroke-gap-change on-stroke-gap-change
                              :on-stroke-style-change on-stroke-style-change
                              :on-stroke-alignment-change on-stroke-alignment-change
                              :on-stroke-cap-start-change on-stroke-cap-start-change
                              :on-stroke-cap-end-change on-stroke-cap-end-change
                              :on-stroke-cap-switch on-stroke-cap-switch
                              :on-toggle-visibility on-toggle-visibility
                              :disable-drag disable-drag
                              :on-focus on-focus
                              :on-blur on-blur
                              :applied-tokens (when (= 0 index) applied-tokens)
                              :on-detach-token on-detach-token
                              :disable-stroke-style disable-stroke-style
                              :select-on-focus (not @disable-drag)
                              :ids ids}])])])]))

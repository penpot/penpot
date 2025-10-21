;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.color :as clr]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.features :as feat]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as deprecated-icon]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def fill-attrs
  #{:fills :hide-fill-on-export})

(def ^:private
  xf:process-fills
  (map-indexed
   (fn [index item]
     (let [color (types.fills/fill->color item)]
       (with-meta item {:index index :color color})))))

(defn- prepare-fills
  "Internal helper hook that prepares fills"
  [fills]
  (if (= :multiple fills)
    fills
    (->> fills
         (into [] xf:process-fills)
         (not-empty))))

(defn- check-props
  "A fills-menu specific memoize check function that only checks if
  specific values are changed on provided props. This allows pass the
  whole shape as values without adding additional rerenders when other
  shape properties changes."
  [n-props o-props]
  (and (identical? (unchecked-get n-props "ids")
                   (unchecked-get o-props "ids"))
       (let [o-vals  (unchecked-get o-props "values")
             n-vals  (unchecked-get n-props "values")
             o-fills (get o-vals :fills)
             n-fills (get n-vals :fills)
             o-applied-tokens (get o-vals :applied-tokens)
             n-applied-tokens (get n-vals :applied-tokens)
             o-hide  (get o-vals :hide-fill-on-export)
             n-hide  (get n-vals :hide-fill-on-export)]
         (and (identical? o-hide n-hide)
              (identical? o-applied-tokens n-applied-tokens)
              (identical? o-fills n-fills)))))

(mf/defc fill-menu*
  {::mf/wrap [#(mf/memo' % check-props)]}
  [{:keys [ids type values applied-tokens]}]

  (let [fills          (get values :fills)
        hide-on-export (get values :hide-fill-on-export false)
        fill-token-applied (:fill applied-tokens)

        render-wasm?   (feat/use-feature "render-wasm/v1")


        ^boolean
        multiple?      (= :multiple fills)

        fills          (mf/with-memo [fills]
                         (prepare-fills fills))

        has-fills?     (or multiple? (some? fills))

        empty-fills?   (and (not multiple?)
                            (= 0 (count fills)))

        open*          (mf/use-state has-fills?)
        open?          (deref open*)

        toggle-content (mf/use-fn #(swap! open* not))
        open-content   (mf/use-fn #(reset! open* true))
        close-content  (mf/use-fn #(reset! open* false))

        checkbox-ref   (mf/use-ref)

        can-add-fills?
        (if render-wasm?
          (and (not multiple?)
               (< (count fills) types.fills/MAX-FILLS))
          (not ^boolean multiple?))

        label
        (case type
          :multiple (tr "workspace.options.selection-fill")
          :group (tr "workspace.options.group-fill")
          (tr "workspace.options.fill"))

        on-add
        (mf/use-fn
         (mf/deps ids multiple? empty-fills?)
         (fn [_]
           (when can-add-fills?
             (st/emit! (udw/trigger-bounding-box-cloaking ids))
             (st/emit! (dc/add-fill ids {:color default-color
                                         :opacity 1}))
             (when (or multiple? empty-fills?)
               (open-content)))))

        on-change
        (mf/use-fn
         (mf/deps ids)
         (fn [color index]
           (let [color (select-keys color clr/color-attrs)]
             (st/emit! (dc/change-fill ids color index)))))

        on-reorder
        (mf/use-fn
         (mf/deps ids)
         (fn [from-pos to-space-between-pos]
           (st/emit! (dc/reorder-fills ids from-pos to-space-between-pos))))

        on-remove
        (mf/use-fn
         (mf/deps ids multiple? empty-fills?)
         (fn [index _event]
           (st/emit! (dc/remove-fill ids index))
           (when (or multiple? empty-fills?)
             (close-content))))

        on-remove-all
        (mf/use-fn
         (mf/deps ids)
         #(st/emit! (dc/remove-all-fills ids)))

        on-detach
        (mf/use-fn
         (mf/deps ids)
         (fn [index _event]
           (st/emit! (dc/detach-fill ids index))))

        on-change-show-on-export
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dc/change-hide-fill-on-export ids (not value))))))

        disable-drag*
        (mf/use-state false)

        disable-drag?
        (deref disable-drag*)

        on-focus
        (mf/use-fn
         #(reset! disable-drag* true))

        on-blur
        (mf/use-fn #(reset! disable-drag* false))

        on-token-change
        (mf/use-fn
         (mf/deps ids)
         (fn [_ token]
           (st/emit!
            (dwta/toggle-token {:token token
                                :attrs #{:fill}
                                :shape-ids ids
                                :expand-with-children true}))))

        on-detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token]
           (st/emit! (dwta/unapply-token {:attributes #{:fill}
                                          :token token
                                          :shape-ids ids}))))]

    (mf/with-layout-effect [hide-on-export]
      (when-let [checkbox (mf/ref-val checkbox-ref)]
        ;; Note that the "indeterminate" attribute only may be set by code, not as a static attribute.
        ;; See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/checkbox#attr-indeterminate
        (if (= hide-on-export :multiple)
          (dom/set-attribute! checkbox "indeterminate" true)
          (dom/remove-attribute! checkbox "indeterminate"))))

    [:div {:class (stl/css :fill-section)}
     [:div {:class (stl/css :fill-title)}
      [:> title-bar* {:collapsable  has-fills?
                      :collapsed    (not open?)
                      :on-collapsed toggle-content
                      :title        label
                      :class        (stl/css-case :fill-title-bar (not has-fills?))}

       (when (not (= :multiple fills))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.fill.add-fill")
                           :on-click on-add
                           :data-testid "add-fill"
                           :disabled (not can-add-fills?)
                           :icon i/add}])]]

     (when open?
       [:div {:class (stl/css :fill-content)}
        (cond
          (= :multiple fills)
          [:div {:class (stl/css :fill-multiple)}
           [:div {:class (stl/css :fill-multiple-label)}
            (tr "settings.multiple")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.fill.remove-fill")
                             :on-click on-remove-all
                             :icon i/remove}]]

          (some? fills)
          [:> h/sortable-container* {}
           (for [value fills]
             (let [mdata (meta value)
                   index (get mdata :index)
                   color (get mdata :color)]
               [:> color-row* {:color color
                               :key index
                               :index index
                               :title (tr "workspace.options.fill")
                               :on-change on-change
                               :on-reorder on-reorder
                               :on-detach on-detach
                               :on-detach-token on-detach-token
                               :on-remove on-remove
                               :disable-drag disable-drag?
                               :on-focus on-focus
                               :applied-token (if (= index 0)
                                                fill-token-applied
                                                nil)
                               :on-token-change on-token-change
                               :origin :fill
                               :select-on-focus (not disable-drag?)
                               :on-blur on-blur}]))])

        (when (or (= type :frame)
                  (and (= type :multiple)
                       (some? hide-on-export)))
          [:div {:class (stl/css :fill-checkbox)}
           [:label {:for "show-fill-on-export"
                    :class (stl/css-case :global/checked (not hide-on-export))}
            [:span {:class (stl/css-case :check-mark true
                                         :checked (not hide-on-export))}
             (when (not hide-on-export)
               deprecated-icon/status-tick)]
            (tr "workspace.options.show-fill-on-export")
            [:input {:type "checkbox"
                     :id "show-fill-on-export"
                     :ref checkbox-ref
                     :checked (not hide-on-export)
                     :on-change on-change-show-on-export}]]])])]))

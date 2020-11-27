;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.frame-grid
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.util.dom :as dom]
   [app.util.data :as d]
   [app.common.math :as mth]
   [app.common.data :refer [parse-integer]]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace.grid :as dw]
   [app.util.geom.grid :as gg]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.util.i18n :as i18n :refer [tr t]]))

(def workspace-saved-grids
  (l/derived :saved-grids refs/workspace-page-options))

(defn- get-size-options [locale]
  [{:value :auto :label (t locale "workspace.options.grid.auto")}
   :separator
   18 12 10 8 6 4 3 2])

(mf/defc grid-options [{:keys [frame grid default-grid-params on-change on-remove on-save-grid]}]
  (let [locale (i18n/use-locale)
        size-options (get-size-options locale)
        state (mf/use-state {:show-advanced-options false
                             :changes {}})
        {:keys [type display params] :as grid} (d/deep-merge grid (:changes @state))

        toggle-advanced-options #(swap! state update :show-advanced-options not)

        emit-changes!
        (fn [update-fn]
          (swap! state update :changes update-fn)
          (when on-change (on-change (d/deep-merge grid (-> @state :changes update-fn)))))

        handle-toggle-visibility
        (fn [event]
          (emit-changes! (fn [changes] (update changes :display #(if (nil? %) false (not %))))))

        handle-remove-grid
        (fn [event]
          (when on-remove (on-remove)))

        handle-change-type
        (fn [type]
          (let [defaults (type default-grid-params)
                keys (keys defaults)
                current-changes (-> @state :changes :params (select-keys keys))
                ;; We give more priority to the current changes
                params (merge defaults current-changes)
                to-merge {:type type :params params}]
            (emit-changes! #(d/deep-merge % to-merge))))

        handle-change
        (fn [& keys]
          (fn [value]
            (emit-changes! #(assoc-in % keys value))))

        handle-change-event
        (fn [& keys]
          (fn [event]
            (let [change-fn (apply handle-change keys)]
              (-> event dom/get-target dom/get-value parse-integer change-fn))))

        handle-change-size
        (fn [size]
          (let [grid (d/deep-merge grid (:changes @state))
                {:keys [margin gutter item-length]} (:params grid)
                frame-length (if (= :column (:type grid)) (:width frame) (:height frame))
                item-length (if (or (nil? size) (= :auto size))
                              (-> (gg/calculate-default-item-length frame-length margin gutter)
                                  (mth/precision 2))
                              item-length)]
            (emit-changes! #(-> %
                                (assoc-in [:params :size] size)
                                (assoc-in [:params :item-length] item-length)))))

        handle-change-item-length
        (fn [item-length]
          (let [{:keys [margin gutter size]} (->> @state :changes :params (d/deep-merge (:params grid)))
                size (if (and (nil? item-length) (or (nil? size) (= :auto size))) 12 size)]
            (emit-changes! #(-> %
                                (assoc-in [:params :size] size)
                                (assoc-in [:params :item-length] item-length)))))

        handle-change-color
        (fn [color]
          (emit-changes! #(-> % (assoc-in [:params :color] color))))

        handle-use-default
        (fn []
          (let [params ((:type grid) default-grid-params)
                color (or (get-in params [:color :value]) (get-in params [:color :color]))
                params (-> params
                           (assoc-in [:color :color] color)
                           (update :color dissoc :value))]
            (emit-changes! #(hash-map :params params))))

        handle-set-as-default
        (fn []
          (let [current-grid (d/deep-merge grid (-> @state :changes))]
            (on-save-grid current-grid)))

        is-default (= (->> @state :changes (d/deep-merge grid) :params)
                      (->> grid :type default-grid-params))]

    [:div.grid-option
     [:div.grid-option-main
      [:button.custom-button {:class (when (:show-advanced-options @state) "is-active")
                              :on-click toggle-advanced-options} i/actions]

      [:& select {:class "flex-grow"
                  :default-value type
                  :options [{:value :square :label (t locale "workspace.options.grid.square")}
                            {:value :column :label (t locale "workspace.options.grid.column")}
                            {:value :row :label (t locale "workspace.options.grid.row")}]
                  :on-change handle-change-type}]

      (if (= type :square)
        [:div.input-element.pixels
         [:> numeric-input {:min "1"
                            :no-validate true
                            :value (:size params)
                            :on-change (handle-change-event :params :size)}]]
        [:& editable-select {:value (:size params)
                             :type (when (number? (:size params)) "number" )
                             :class "input-option"
                             :options size-options
                             :placeholder "Auto"
                             :on-change handle-change-size}])

      [:div.grid-option-main-actions
       [:button.custom-button {:on-click handle-toggle-visibility} (if display i/eye i/eye-closed)]
       [:button.custom-button {:on-click handle-remove-grid} i/minus]]]

     [:& advanced-options {:visible? (:show-advanced-options @state)
                           :on-close toggle-advanced-options}
      (when (= :square type)
        [:& input-row {:label (t locale "workspace.options.grid.params.size")
                       :class "pixels"
                       :min 1
                       :value (:size params)
                       :on-change (handle-change :params :size)}])

      (when (= :row type)
        [:& input-row {:label (t locale "workspace.options.grid.params.rows")
                       :type :editable-select
                       :options size-options
                       :value (:size params)
                       :min 1
                       :placeholder "Auto"
                       :on-change handle-change-size}])

      (when (= :column type)
        [:& input-row {:label (t locale "workspace.options.grid.params.columns")
                       :type :editable-select
                       :options size-options
                       :value (:size params)
                       :min 1
                       :placeholder "Auto"
                       :on-change handle-change-size}])

      (when (#{:row :column} type)
        [:& input-row {:label (t locale "workspace.options.grid.params.type")
                       :type :select
                       :options [{:value :stretch :label (t locale "workspace.options.grid.params.type.stretch")}
                                 {:value :left :label (if (= type :row)
                                                        (t locale "workspace.options.grid.params.type.top")
                                                        (t locale "workspace.options.grid.params.type.left"))}
                                 {:value :center :label (t locale "workspace.options.grid.params.type.center")}
                                 {:value :right :label (if (= type :row)
                                                         (t locale "workspace.options.grid.params.type.bottom")
                                                         (t locale "workspace.options.grid.params.type.right"))}]
                       :value (:type params)
                       :on-change (handle-change :params :type)}])

      (when (#{:row :column} type)
        [:& input-row {:label (if (= :row type)
                                (t locale "workspace.options.grid.params.height")
                                (t locale "workspace.options.grid.params.width"))
                       :class "pixels"
                       :placeholder "Auto"
                       :value (or (:item-length params) "")
                       :on-change handle-change-item-length}])

      (when (#{:row :column} type)
        [:*
         [:& input-row {:label (t locale "workspace.options.grid.params.gutter")
                        :class "pixels"
                        :value (:gutter params)
                        :min 0
                        :placeholder "0"
                        :on-change (handle-change :params :gutter)}]
         [:& input-row {:label (t locale "workspace.options.grid.params.margin")
                        :class "pixels"
                        :min 0
                        :placeholder "0"
                        :value (:margin params)
                        :on-change (handle-change :params :margin)}]])

      [:& color-row {:color (:color params)
                     :disable-gradient true
                     :on-change handle-change-color}]
      [:div.row-flex
       [:button.btn-options {:disabled is-default
                             :on-click handle-use-default} (t locale "workspace.options.grid.params.use-default")]
       [:button.btn-options {:disabled is-default
                             :on-click handle-set-as-default} (t locale "workspace.options.grid.params.set-default")]]]]))

(mf/defc frame-grid [{:keys [shape]}]
  (let [locale (i18n/use-locale)
        id (:id shape)
        default-grid-params (merge dw/default-grid-params (mf/deref workspace-saved-grids))
        handle-create-grid #(st/emit! (dw/add-frame-grid id))
        handle-remove-grid (fn [index] #(st/emit! (dw/remove-frame-grid id index)))
        handle-edit-grid (fn [index] #(st/emit! (dw/set-frame-grid id index %)))
        handle-save-grid (fn [grid] (st/emit! (dw/set-default-grid (:type grid) (:params grid))))]
    [:div.element-set
     [:div.element-set-title
      [:span (t locale "workspace.options.grid.title")]
      [:div.add-page {:on-click handle-create-grid} i/close]]

     (when (not (empty? (:grids shape)))
       [:div.element-set-content
        (for [[index grid] (map-indexed vector (:grids shape))]
          [:& grid-options {:key (str (:id shape) "-" index)
                              :grid grid
                              :default-grid-params default-grid-params
                              :frame shape
                              :on-change (handle-edit-grid index)
                              :on-remove (handle-remove-grid index)
                              :on-save-grid handle-save-grid}])])]))



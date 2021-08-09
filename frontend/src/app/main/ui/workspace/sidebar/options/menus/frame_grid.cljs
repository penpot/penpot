;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.frame-grid
  (:require
   [app.common.math :as mth]
   [app.main.data.workspace.grid :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [app.util.data :as d]
   [app.util.geom.grid :as gg]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def workspace-saved-grids
  (l/derived :saved-grids refs/workspace-page-options))

(defn- get-size-options []
  [{:value :auto :label (tr "workspace.options.grid.auto")}
   :separator
   18 12 10 8 6 4 3 2])

(mf/defc grid-options
  [{:keys [grid frame default-grid-params on-change on-remove on-save-grid]}]
  (let [size-options (get-size-options)
        state (mf/use-state {:show-advanced-options false})
        {:keys [type display params]} grid

        toggle-advanced-options
        #(swap! state update :show-advanced-options not)

        handle-toggle-visibility
        (fn [_]
          (when on-change
            (on-change (update grid :display #(if (nil? %) false (not %))))))

        handle-remove-grid
        (fn [_]
          (when on-remove (on-remove)))

        handle-change-type
        (fn [grid-type]
          (let [defaults (grid-type default-grid-params)]
            (when on-change
              (on-change (assoc grid
                                :type grid-type
                                :params defaults)))))

        handle-change
        (fn [& keys]
          (fn [value]
            (when on-change
              (on-change (assoc-in grid keys value)))))

        handle-change-size
        (fn [size]
          (let [{:keys [margin gutter item-length]} (:params grid)
                frame-length (if (= :column (:type grid)) (:width frame) (:height frame))
                item-length (if (or (nil? size) (= :auto size))
                              (-> (gg/calculate-default-item-length frame-length margin gutter)
                                  (mth/precision 2))
                              item-length)]
            (when on-change
              (on-change (-> grid
                             (assoc-in [:params :size] size)
                             (assoc-in [:params :item-length] item-length))))))

        handle-change-item-length
        (fn [item-length]
          (let [size (get-in grid [:params :size])
                size (if (and (nil? item-length) (or (nil? size) (= :auto size))) 12 size)]
            (when on-change
              (on-change (-> grid
                             (assoc-in [:params :size] size)
                             (assoc-in [:params :item-length] item-length))))))

        handle-change-color
        (fn [color]
          (when on-change
            (on-change (assoc-in grid [:params :color] color))))

        handle-detach-color
        (fn []
          (when on-change
            (on-change (-> grid
                           (d/dissoc-in [:params :color :id])
                           (d/dissoc-in [:params :color :file-id])))))

        handle-use-default
        (fn []
          (let [params ((:type grid) default-grid-params)
                color (or (get-in params [:color :value]) (get-in params [:color :color]))
                params (-> params
                           (assoc-in [:color :color] color)
                           (update :color dissoc :value))]
            (when on-change
              (on-change (assoc grid :params params)))))

        handle-set-as-default
        (fn []
          (when on-save-grid
            (on-save-grid grid)))

        is-default (= (->> grid :params)
                      (->> grid :type default-grid-params))

        open? (:show-advanced-options @state)]

    [:div.grid-option
     [:div.grid-option-main {:style {:display (when open? "none")}}
      [:button.custom-button {:class (when open? "is-active")
                              :on-click toggle-advanced-options} i/actions]

      [:& select {:class "flex-grow"
                  :default-value type
                  :options [{:value :square :label (tr "workspace.options.grid.square")}
                            {:value :column :label (tr "workspace.options.grid.column")}
                            {:value :row :label (tr "workspace.options.grid.row")}]
                  :on-change handle-change-type}]

      (if (= type :square)
        [:div.input-element.pixels
         [:> numeric-input {:min 1
                            :no-validate true
                            :value (:size params)
                            :on-change (handle-change :params :size)}]]
        [:& editable-select {:value (:size params)
                             :type (when (number? (:size params)) "number" )
                             :class "input-option"
                             :min 1
                             :options size-options
                             :placeholder "Auto"
                             :on-change handle-change-size}])

      [:div.grid-option-main-actions
       [:button.custom-button {:on-click handle-toggle-visibility} (if display i/eye i/eye-closed)]
       [:button.custom-button {:on-click handle-remove-grid} i/minus]]]

     [:& advanced-options {:visible? open?
                           :on-close toggle-advanced-options}
      [:button.custom-button {:on-click toggle-advanced-options} i/actions]
      (when (= :square type)
        [:& input-row {:label (tr "workspace.options.grid.params.size")
                       :class "pixels"
                       :min 1
                       :value (:size params)
                       :on-change (handle-change :params :size)}])

      (when (= :row type)
        [:& input-row {:label (tr "workspace.options.grid.params.rows")
                       :type :editable-select
                       :options size-options
                       :value (:size params)
                       :min 1
                       :placeholder "Auto"
                       :on-change handle-change-size}])

      (when (= :column type)
        [:& input-row {:label (tr "workspace.options.grid.params.columns")
                       :type :editable-select
                       :options size-options
                       :value (:size params)
                       :min 1
                       :placeholder "Auto"
                       :on-change handle-change-size}])

      (when (#{:row :column} type)
        [:& input-row {:label (tr "workspace.options.grid.params.type")
                       :type :select
                       :options [{:value :stretch :label (tr "workspace.options.grid.params.type.stretch")}
                                 {:value :left :label (if (= type :row)
                                                        (tr "workspace.options.grid.params.type.top")
                                                        (tr "workspace.options.grid.params.type.left"))}
                                 {:value :center :label (tr "workspace.options.grid.params.type.center")}
                                 {:value :right :label (if (= type :row)
                                                         (tr "workspace.options.grid.params.type.bottom")
                                                         (tr "workspace.options.grid.params.type.right"))}]
                       :value (:type params)
                       :on-change (handle-change :params :type)}])

      (when (#{:row :column} type)
        [:& input-row {:label (if (= :row type)
                                (tr "workspace.options.grid.params.height")
                                (tr "workspace.options.grid.params.width"))
                       :class "pixels"
                       :placeholder "Auto"
                       :value (or (:item-length params) "")
                       :on-change handle-change-item-length}])

      (when (#{:row :column} type)
        [:*
         [:& input-row {:label (tr "workspace.options.grid.params.gutter")
                        :class "pixels"
                        :value (:gutter params)
                        :min 0
                        :placeholder "0"
                        :on-change (handle-change :params :gutter)}]
         [:& input-row {:label (tr "workspace.options.grid.params.margin")
                        :class "pixels"
                        :min 0
                        :placeholder "0"
                        :value (:margin params)
                        :on-change (handle-change :params :margin)}]])

      [:& color-row {:color (:color params)
                     :disable-gradient true
                     :on-change handle-change-color
                     :on-detach handle-detach-color}]
      [:div.row-flex
       [:button.btn-options {:disabled is-default
                             :on-click handle-use-default} (tr "workspace.options.grid.params.use-default")]
       [:button.btn-options {:disabled is-default
                             :on-click handle-set-as-default} (tr "workspace.options.grid.params.set-default")]]]]))

(mf/defc frame-grid [{:keys [shape]}]
  (let [id (:id shape)
        default-grid-params (merge dw/default-grid-params (mf/deref workspace-saved-grids))
        handle-create-grid #(st/emit! (dw/add-frame-grid id))
        handle-remove-grid (fn [index] #(st/emit! (dw/remove-frame-grid id index)))
        handle-edit-grid (fn [index] #(st/emit! (dw/set-frame-grid id index %)))
        handle-save-grid (fn [grid] (st/emit! (dw/set-default-grid (:type grid) (:params grid))))]
    [:div.element-set
     [:div.element-set-title
      [:span (tr "workspace.options.grid.title")]
      [:div.add-page {:on-click handle-create-grid} i/close]]

     (when (seq (:grids shape))
       [:div.element-set-content
        (for [[index grid] (map-indexed vector (:grids shape))]
          [:& grid-options {:key (str (:id shape) "-" index)
                              :grid grid
                              :default-grid-params default-grid-params
                              :frame shape
                              :on-change (handle-edit-grid index)
                              :on-remove (handle-remove-grid index)
                              :on-save-grid handle-save-grid}])])]))



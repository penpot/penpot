;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.frame-layouts
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.dom :as dom]
   [uxbox.util.data :as d]
   [uxbox.common.data :refer [parse-integer]]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [uxbox.main.ui.workspace.sidebar.options.rows.input-row :refer [input-row]]
   [uxbox.main.ui.components.select :refer [select]]
   [uxbox.main.ui.components.editable-select :refer [editable-select]]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]))

(mf/defc advanced-options [{:keys [visible? on-close children]}]
  (when visible?
    [:*
     [:div.focus-overlay {:on-click #(when on-close
                                       (do
                                         (dom/stop-propagation %)
                                         (on-close)))}]
     [:div.advanced-options {}
      children]]))

(def ^:private size-options
  [{:value :auto :label "Auto"}
   :separator
   18 12 10 8 6 4 3 2])

(mf/defc layout-options [{:keys [layout default-layout-params on-change on-remove on-save-layout]}]
  (let [state (mf/use-state {:show-advanced-options false
                             :changes {}})
        {:keys [type display params] :as layout} (d/deep-merge layout (:changes @state))

        toggle-advanced-options #(swap! state update :show-advanced-options not)

        emit-changes!
        (fn [update-fn]
          (swap! state update :changes update-fn)
          (when on-change (on-change (d/deep-merge layout (-> @state :changes update-fn)))))

        handle-toggle-visibility
        (fn [event]
          (emit-changes! (fn [changes] (update changes :display #(if (nil? %) false (not %))))))

        handle-remove-layout
        (fn [event]
          (when on-remove (on-remove)))

        handle-change-type
        (fn [type]
          (let [defaults (type default-layout-params)
                keys (keys defaults)
                params (->> @state :changes params (select-keys keys) (merge defaults))
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

        handle-use-default
        (fn []
          (emit-changes! #(hash-map :params ((:type layout) default-layout-params))))

        handle-set-as-default
        (fn []
          (let [current-layout (d/deep-merge layout (-> @state :changes))]
            (on-save-layout current-layout)))

        is-default (= (->> @state :changes (d/deep-merge layout) :params)
                      (->> layout :type default-layout-params))]

    [:div.grid-option
     [:div.grid-option-main
      [:button.custom-button {:class (when (:show-advanced-options @state) "is-active")
                              :on-click toggle-advanced-options} i/actions]

      [:& select {:class "flex-grow"
                  :default-value type
                  :options [{:value :square :label "Square"}
                            {:value :column :label "Columns"}
                            {:value :row :label "Rows"}]
                  :on-change handle-change-type}]

      (if (= type :square)
        [:div.input-element.pixels
         [:input.input-text {:type "number"
                             :min "1"
                             :no-validate true
                             :value (:size params)
                             :on-change (handle-change-event :params :size)}]]
        [:& editable-select {:value (:size params)
                             :type (when (number? (:size params)) "number" )
                             :class "input-option"
                             :options size-options
                             :on-change (handle-change :params :size)}])

      [:div.grid-option-main-actions
       [:button.custom-button {:on-click handle-toggle-visibility} (if display i/eye i/eye-closed)]
       [:button.custom-button {:on-click handle-remove-layout} i/trash]]]

     [:& advanced-options {:visible? (:show-advanced-options @state)
                           :on-close toggle-advanced-options}
      (when (= :square type)
        [:& input-row {:label "Size"
                       :class "pixels"
                       :min 1
                       :value (:size params)
                       :on-change (handle-change :params :size)}])

      (when (= :row type)
        [:& input-row {:label "Rows"
                       :type :editable-select
                       :options size-options
                       :value (:size params)
                       :min 1
                       :on-change (handle-change :params :size)}])

      (when (= :column type)
        [:& input-row {:label "Columns"
                       :type :editable-select
                       :options size-options
                       :value (:size params)
                       :min 1
                       :on-change (handle-change :params :size)}])

      (when (#{:row :column} type)
        [:& input-row {:label "Type"
                       :type :select
                       :options [{:value :stretch :label "Stretch"}
                                 {:value :left :label "Left"}
                                 {:value :center :label "Center"}
                                 {:value :right :label "Right"}]
                       :value (:type params)
                       :on-change (handle-change :params :type)}])

      (when (= :row type)
        [:& input-row {:label "Height"
                       :class "pixels"
                       :min 1
                       :value (or (:item-height params) "")
                       :on-change (handle-change :params :item-height)}])

      (when (= :column type)
        [:& input-row {:label "Width"
                       :class "pixels"
                       :value (or (:item-width params) "")
                       :on-change (handle-change :params :item-width)}])

      (when (#{:row :column} type)
        [:*
         [:& input-row {:label "Gutter"
                        :class "pixels"
                        :value (:gutter params)
                        :min 0
                        :on-change (handle-change :params :gutter)}]
         [:& input-row {:label "Margin"
                        :class "pixels"
                        :min 0
                        :value (:margin params)
                        :on-change (handle-change :params :margin)}]])

      [:& color-row {:value (:color params)
                     :on-change (handle-change :params :color)}]
      [:div.row-flex
       [:button.btn-options {:disabled is-default
                             :on-click handle-use-default} "Use default"]
       [:button.btn-options {:disabled is-default
                             :on-click handle-set-as-default} "Set as default"]]]]))

(mf/defc frame-layouts [{:keys [shape]}]
  (let [id (:id shape)
        default-layout-params (merge dw/default-layout-params (mf/deref refs/workspace-saved-layouts))
        handle-create-layout #(st/emit! (dw/add-frame-layout id))
        handle-remove-layout (fn [index] #(st/emit! (dw/remove-frame-layout id index)))
        handle-edit-layout (fn [index] #(st/emit! (dw/set-frame-layout id index %)))
        handle-save-layout (fn [layout] (st/emit! (dw/set-default-layout (:type layout) (:params layout))))]
    [:div.element-set
     [:div.element-set-title
      [:span "Grid & Layout"]
      [:div.add-page {:on-click handle-create-layout} i/close]]

     (when (not (empty? (:layouts shape)))
       [:div.element-set-content
        (for [[index layout] (map-indexed vector (:layouts shape))]
          [:& layout-options {:key (str (:id shape) "-" index)
                              :layout layout
                              :default-layout-params default-layout-params
                              :on-change (handle-edit-layout index)
                              :on-remove (handle-remove-layout index)
                              :on-save-layout handle-save-layout}])])]))


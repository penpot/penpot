;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.editable-select
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as timers]
   [rumext.v2 :as mf]))

(defn on-number-input-key-down [{:keys [event min-val max-val set-value!]}]
  (let [up? (kbd/up-arrow? event)
        down? (kbd/down-arrow? event)]
    (when (or up? down?)
      (dom/prevent-default event)
      (let [value (-> event dom/get-target dom/get-value)
            value (or (d/parse-double value) value)
            increment (cond
                        (kbd/shift? event) (if up? 10 -10)
                        (kbd/alt? event) (if up? 0.1 -0.1)
                        :else (if up? 1 -1))
            new-value (+ value increment)
            new-value (cond
                        (and (d/num? min-val) (< new-value min-val)) min-val
                        (and (d/num? max-val) (> new-value max-val)) max-val
                        :else new-value)]
        (set-value! new-value)))))

(mf/defc dropdown-select [{:keys [on-close element-id element-ref options on-select]}]
  [:& dropdown {:show true
                :on-close on-close}
   [:div {:class (stl/css :custom-select-dropdown)
          :ref element-ref}
    [:ul {:class (stl/css :custom-select-dropdown-list)}
     (for [[index item] (d/enumerate options)]
       (cond
         (= :separator item) [:li {:class (stl/css :separator)
                                   :key (dm/str element-id "-" index)}]
         :else (let [{:keys [value label selected?]} item]
                 [:li
                  {:key (str element-id "-" index)
                   :class (stl/css-case :dropdown-element true
                                        :is-selected selected?)
                   :data-label label
                   :on-click on-select}
                  [:span {:class (stl/css :label)} label]
                  [:span {:class (stl/css :value)} value]
                  [:span {:class (stl/css :check-icon)} i/tick]])))]]])

(mf/defc editable-select
  [{:keys [value type options class on-change placeholder on-blur input-class] :as params}]
  (let [state* (mf/use-state {:id (uuid/next)
                              :is-open? false
                              :current-value value
                              :current-item nil
                              :top nil
                              :left nil
                              :bottom nil})
        state (deref state*)
        is-open? (:is-open? state)
        current-value (:current-value state)
        element-id (:id state)

        min-val (get params :min)
        max-val (get params :max)

        multiple? (= :multiple value)
        token (when-not multiple?
                (-> (filter :selected? options) (first)))
        _ (js/console.log "token" token)

        emit-blur? (mf/use-ref nil)
        select-wrapper-ref (mf/use-ref)

        toggle-dropdown
        (mf/use-fn
         (mf/deps state)
         #(swap! state* update :is-open? not))

        close-dropdown
        (fn [event]
          (dom/stop-propagation event)
          (swap! state* assoc :is-open? false))

        labels-map   (->> (map (fn [{:keys [label] :as item}]
                                 [label item])
                               options)
                          (into {}))

        set-value
        (fn [value]
          (swap! state* assoc :current-value value)
          (when on-change (on-change value)))

        select-item
        (mf/use-fn
         (mf/deps on-change on-blur labels-map)
         (fn [event]
           (let [label (-> (dom/get-current-target event)
                           (dom/get-data "label")
                           (d/read-string)
                           (str))
                 {:keys [value] :as item} (get labels-map label)]
             (swap! state* assoc
                    :current-value value
                    :current-item item)
             (when on-change (on-change item))
             (when on-blur (on-blur)))))

        handle-change-input
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value)
                value (or (d/parse-double value) value)]
            (set-value value)))

        on-node-load
        (fn [node]
          ;; There is a problem when changing the state in this callback that
          ;; produces the dropdown to close in the same event
          (when node
            (timers/schedule
             #(when-let [bounds (when node (dom/get-bounding-rect node))]
                (let [{window-height :height} (dom/get-window-size)
                      {:keys [left top height]} bounds
                      bottom (when (< (- window-height top) 300) (- window-height top))
                      top (when (>= (- window-height top) 300) (+ top height))]
                  (swap! state*
                         assoc
                         :left left
                         :top top
                         :bottom bottom))))))

        handle-key-down
        (mf/use-fn
         (mf/deps set-value is-open?)
         (fn [event]
           (cond
             is-open? (let [up? (kbd/up-arrow? event)
                            down? (kbd/down-arrow? event)]
                        (dom/prevent-default event)
                        (js/console.log "up? down?" up? down?))
             (= type "number") (on-number-input-key-down {:event event
                                                          :min-val min-val
                                                          :max-val max-val
                                                          :set-value! set-value}))))

        handle-focus
        (mf/use-fn
         (fn []
           (mf/set-ref-val! emit-blur? false)))

        handle-blur
        (mf/use-fn
         (fn []
           (mf/set-ref-val! emit-blur? true)
           (timers/schedule
            200
            (fn []
              (when (and on-blur (mf/ref-val emit-blur?)) (on-blur))))))]

    (mf/use-effect
     (mf/deps value current-value)
     #(when (not= (str value) current-value)
        (swap! state* assoc :current-value value)))

    (mf/with-effect [is-open?]
      (let [wrapper-node (mf/ref-val select-wrapper-ref)
            node (dom/get-element-by-class "checked-element is-selected" wrapper-node)
            nodes (dom/get-elements-by-class "checked-element-value" wrapper-node)
            closest (fn [a b] (first (sort-by #(mth/abs (- % b)) a)))
            closest-value (str (closest options value))]
        (when is-open?
          (if  (some? node)
            (dom/scroll-into-view-if-needed! node)
            (some->> nodes
                     (d/seek #(= closest-value (dom/get-inner-text %)))
                     (dom/scroll-into-view-if-needed!)))))

      (mf/set-ref-val! emit-blur? (not is-open?)))


    [:div {:class (dm/str class " " (stl/css :editable-select))
           :ref on-node-load}
     (when token
       [:div {:class (stl/css :token-pill)}
        (:label token)])
     (cond
       token [:input {:value ""
                      :class input-class
                      :on-change handle-change-input
                      :on-key-down handle-key-down
                      :on-focus handle-focus
                      :on-blur handle-blur
                      :type type}]
       (= type "number") [:> numeric-input* {:value (or current-value "")
                                             :className input-class
                                             :on-change set-value
                                             :on-focus handle-focus
                                             :on-blur handle-blur
                                             :placeholder placeholder}]
       :else [:input {:value (or current-value "")
                      :class input-class
                      :on-change handle-change-input
                      :on-key-down handle-key-down
                      :on-focus handle-focus
                      :on-blur handle-blur
                      :placeholder placeholder
                      :type type}])

     (when (seq options)
       [:span {:class (stl/css :dropdown-button)
               :on-click toggle-dropdown}
        i/arrow])

     (when (and is-open? (seq options))
       [:& dropdown-select {:on-close close-dropdown
                            :element-id element-id
                            :element-ref select-wrapper-ref
                            :options options
                            :on-select select-item}])]))

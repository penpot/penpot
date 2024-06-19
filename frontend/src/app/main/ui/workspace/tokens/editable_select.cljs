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
   [app.main.data.shortcuts :as dsc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.core :as wtc]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [app.util.timers :as timers]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

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

(defn direction-select
  "Returns next `n` in `direction` while wrapping around at the last item at the count of `coll`.

  `direction` accepts `:up` or `:down`."
  [direction n coll]
  (let [last-n (dec (count coll))
        next-n (case direction
                 :up (dec n)
                 :down (inc n))
        wrap-around-n (cond
                        (neg? next-n) last-n
                        (> next-n last-n) 0
                        :else next-n)]
    wrap-around-n))

(mf/defc dropdown-select [{:keys [position on-close element-id element-ref options on-select]}]
  (let [highlighted* (mf/use-state nil)
        highlighted (deref highlighted*)
        on-keyup (fn [event]
                   (cond
                     (and (kbd/enter? event) highlighted) (on-select (nth options highlighted))
                     (kbd/up-arrow? event) (do
                                             (dom/prevent-default event)
                                             (->> (direction-select :up (or highlighted 0) options)
                                                  (reset! highlighted*)))
                     (kbd/down-arrow? event) (do
                                               (dom/prevent-default event)
                                               (->> (direction-select :down (or highlighted -1) options)
                                                    (reset! highlighted*)))))]
    (mf/with-effect [highlighted]
      (let [keys [(events/listen globals/document EventType.KEYUP on-keyup)
                  (events/listen globals/document EventType.KEYDOWN dom/prevent-default)]]
        (st/emit! (dsc/push-shortcuts :token {}))
        (fn []
          (doseq [key keys]
            (events/unlistenByKey key))
          (st/emit! (dsc/pop-shortcuts :token)))))
    [:& dropdown {:show true
                  :on-close on-close}
     [:> :div {:class (stl/css-case :custom-select-dropdown true
                                    :custom-select-dropdown-right (= position :right)
                                    :custom-select-dropdown-left (= position :left))
               :on-mouse-enter #(reset! highlighted* nil)
               :ref element-ref}
      [:ul {:class (stl/css :custom-select-dropdown-list)}
       (for [[index item] (d/enumerate options)]
         (cond
           (= :separator item) [:li {:class (stl/css :separator)
                                     :key (dm/str element-id "-" index)}]
           ;; Remove items with missing references
           (seq (:errors item)) nil
           :else (let [{:keys [label selected? errors]} item
                       highlighted? (= highlighted index)]
                   [:li
                    {:key (str element-id "-" index)
                     :class (stl/css-case :dropdown-element true
                                          :is-selected selected?
                                          :is-highlighted highlighted?)
                     :data-label label
                     :disabled (seq errors)
                     :on-click #(on-select item)}
                    [:span {:class (stl/css :label)} label]
                    [:span {:class (stl/css :value)} (wtc/resolve-token-value item)]
                    [:span {:class (stl/css :check-icon)} i/tick]])))]]]))

(mf/defc editable-select
  [{:keys [value options disabled class on-change placeholder on-blur on-token-remove position input-props] :as params}]
  (let [{:keys [type]} input-props
        input-class (:class input-props)
        state* (mf/use-state {:id (uuid/next)
                              :is-open? false
                              :current-value value
                              :token-value nil
                              :current-item nil
                              :top nil
                              :left nil
                              :bottom nil})
        state (deref state*)
        is-open? (:is-open? state)
        refocus? (:refocus? state)
        current-value (:current-value state)
        element-id (:id state)

        min-val (get params :min)
        max-val (get params :max)

        multiple? (= :multiple value)
        token (when-not multiple?
                (-> (filter :selected? options) (first)))

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

        set-token-value!
        (fn [value]
          (swap! state* assoc :token-value value))

        set-value
        (fn [value event]
          (swap! state* assoc
                 :current-value value
                 :token-value value)
          (when on-change (on-change value event)))

        select-item
        (mf/use-fn
         (mf/deps on-change on-blur labels-map)
         (fn [{:keys [value] :as item}]
           (swap! state* assoc
                  :current-value value
                  :token-value nil
                  :current-item item)
           (when on-change (on-change item))
           (when on-blur (on-blur))))

        handle-change-input
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value)
                value (or (d/parse-double value) value)]
            (set-value value event)))

        handle-token-change-input
        (fn [event]
          (let [value (-> event dom/get-target dom/get-value)
                value (or (d/parse-double value) value)]
            (set-token-value! value)))

        handle-key-down
        (mf/use-fn
         (mf/deps set-value is-open? token)
         (fn [^js event]
           (cond
             token (let [backspace? (kbd/backspace? event)
                         enter? (kbd/enter? event)
                         value (-> event dom/get-target dom/get-value)
                         caret-at-beginning? (zero? (.. event -target -selectionStart))
                         no-text-selected? (str/empty? (.toString (js/document.getSelection)))
                         delete-token? (and backspace? caret-at-beginning? no-text-selected?)
                         replace-token-with-value? (and enter? (seq (str/trim value)))]
                     (cond
                       delete-token? (do
                                       (dom/prevent-default event)
                                       (on-token-remove token)
                                       ;; Re-focus the input value of the newly rendered input element
                                       (swap! state* assoc :refocus? true))
                       replace-token-with-value? (do
                                                   (dom/prevent-default event)
                                                   (on-token-remove token)
                                                   (handle-change-input event)
                                                   (set-token-value! nil))
                       :else (set-token-value! value)))
             (= type "number") (on-number-input-key-down {:event event
                                                          :min-val min-val
                                                          :max-val max-val
                                                          :set-value! set-value}))))

        handle-focus
        (mf/use-fn
         (mf/deps refocus?)
         (fn []
           (when refocus?
             (swap! state* dissoc :refocus?))
           (mf/set-ref-val! emit-blur? false)))

        handle-blur
        (mf/use-fn
         (fn []
           (mf/set-ref-val! emit-blur? true)
           (swap! state* assoc :token-value nil)
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


    [:div {:class (dm/str class " " (stl/css-case :editable-select true
                                                  :editable-select-disabled disabled))}
     (when-let [{:keys [label value]} token]
       [:div {:title (str label ": " value)
              :class (stl/css :token-pill)}
        (wtc/resolve-token-value token)])
     (cond
       token [:& :input (merge input-props
                               {:value (or (:token-value state) "")
                                :type "text"
                                :class input-class
                                :onChange handle-token-change-input
                                :onKeyDown handle-key-down
                                :onFocus handle-focus
                                :onBlur handle-blur})]
       (= type "number") [:& numeric-input* (merge input-props
                                                   {:autoFocus refocus?
                                                    :value (or current-value "")
                                                    :className input-class
                                                    :onChange set-value
                                                    :onFocus handle-focus
                                                    :onBlur handle-blur
                                                    :placeholder placeholder})]
       :else [:& :input (merge input-props
                               {:value (or current-value "")
                                :class input-class
                                :onChange handle-change-input
                                :onKeyDown handle-key-down
                                :onFocus handle-focus
                                :onBlur handle-blur
                                :placeholder placeholder
                                :type type})])

     (when (seq options)
       [:div {:class (stl/css :dropdown-button)
              :on-click toggle-dropdown}
        i/arrow])

     (when (and is-open? (seq options))
       [:& dropdown-select {:position position
                            :on-close close-dropdown
                            :element-id element-id
                            :element-ref select-wrapper-ref
                            :options options
                            :on-select select-item}])]))

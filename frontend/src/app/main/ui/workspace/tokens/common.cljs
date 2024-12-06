;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.common
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.shortcuts :as dsc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf])
  (:import goog.events.EventType))

;; Helpers ---------------------------------------------------------------------

(defn camel-keys [m]
  (->> m
       (d/deep-mapm
        (fn [[k v]]
          (if (or (keyword? k) (string? k))
            [(keyword (str/camel (name k))) v]
            [k v])))))

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

(defn use-arrow-highlight [{:keys [shortcuts-key options on-select]}]
  (let [highlighted* (mf/use-state nil)
        highlighted (deref highlighted*)
        on-dehighlight #(reset! highlighted* nil)
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
      (let [shortcuts-key shortcuts-key
            keys [(events/listen globals/document EventType.KEYUP on-keyup)
                  (events/listen globals/document EventType.KEYDOWN dom/prevent-default)]]
        (st/emit! (dsc/push-shortcuts shortcuts-key {}))
        (fn []
          (doseq [key keys]
            (events/unlistenByKey key))
          (st/emit! (dsc/pop-shortcuts shortcuts-key)))))
    {:highlighted highlighted
     :on-dehighlight on-dehighlight}))

(defn use-dropdown-open-state []
  (let [open? (mf/use-state false)
        on-open (mf/use-fn #(reset! open? true))
        on-close (mf/use-fn #(reset! open? false))
        on-toggle (mf/use-fn #(swap! open? not))]
    {:dropdown-open? @open?
     :on-open-dropdown on-open
     :on-close-dropdown on-close
     :on-toggle-dropdown on-toggle}))

;; Components ------------------------------------------------------------------

(mf/defc dropdown-select
  [{:keys [id _shortcuts-key options on-close element-ref on-select] :as props}]
  (let [{:keys [highlighted on-dehighlight]} (use-arrow-highlight props)]
    [:& dropdown {:show true
                  :on-close on-close}
     [:> :div {:class (stl/css :dropdown)
               :on-mouse-enter on-dehighlight
               :ref element-ref}
      [:ul {:class (stl/css :dropdown-list)}
       (for [[index item] (d/enumerate options)]
         (cond
           (= :separator item)
           [:li {:class (stl/css :separator)
                 :key (dm/str id "-" index)}]
           :else
           (let [{:keys [label selected? disabled?]} item
                 highlighted? (= highlighted index)]
             [:li
              {:key (str id "-" index)
               :class (stl/css-case :dropdown-element true
                                    :is-selected selected?
                                    :is-highlighted highlighted?)
               :data-label label
               :disabled disabled?
               :on-click #(on-select item)}
              [:span {:class (stl/css :label)} label]
              [:span {:class (stl/css :check-icon)} i/tick]])))]]]))

(mf/defc labeled-input
  {::mf/wrap-props false}
  [{:keys [label input-props auto-complete? error? render-right]}]
  (let [input-props (cond-> input-props
                      :always camel-keys
                      ;; Disable auto-complete on form fields for proprietary password managers
                      ;; https://github.com/orgs/tokens-studio/projects/69/views/11?pane=issue&itemId=63724204
                      (not auto-complete?) (assoc "data-1p-ignore" true
                                                  "data-lpignore" true
                                                  :auto-complete "off"))]
    [:label {:class (stl/css-case :labeled-input true
                                  :labeled-input-error error?)}
     [:span {:class (stl/css :label)} label]
     [:& :input input-props]
     (when render-right
       [:& render-right])]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.common
  (:require
   [app.common.data :as d]
   [app.main.data.shortcuts :as dsc]
   [app.main.store :as st]
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

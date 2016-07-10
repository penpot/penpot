;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.ui.viewer.interactions
  (:require [uxbox.util.dom :as dom]
            [vendor.animejs]))

(defn- translate-trigger
  "Translates the interaction trigger name (keyword) into
  approriate dom event name (keyword)."
  [trigger]
  {:pre [(keyword? trigger)]
   :post [(keyword? %)]}
  (case trigger
    :click :on-click
    :hover :on-hover
    (throw (ex-info "not supported at this moment" {:trigger trigger}))))

(defn- translate-ease
  "Translates the uxbox ease settings to one
  that are compatible with anime.js library."
  [ease]
  {:pre [(keyword? ease)]
   :post [(string? %)]}
  (case ease
    :easein "easeInCubic"
    :easeout "easeOutCubic"
    :easeinout "easeInOutCubic"
    (name ease)))

;; --- Interactions to Animation Compilation

(defn- build-moveby-interaction
  [{:keys [element moveby-x moveby-y easing delay duration]}]
  (let [opts (clj->js {:targets [(str "#shape-" element)]
                       :translateX (str moveby-x "px")
                       :translateY (str moveby-y "px")
                       :easing (translate-ease easing)
                       :delay delay
                       :duration duration
                       :loop false})]
    #(js/anime opts)))

(defn- build-gotourl-interaction
  [{:keys [url]}]
  #(set! (.-href js/location) url))

(defn- build-interaction
  "Given an interaction data structure return
  a precompiled animation."
  [{:keys [action] :as itx}]
  (case action
    :moveby (build-moveby-interaction itx)
    :gotourl (build-gotourl-interaction itx)
    (throw (ex-info "undefined interaction" {:action action}))))

;; --- Main Api

(defn- default-callback
  "A default user action callback that prevents default event."
  [itx event]
  (dom/prevent-default event)
  (itx))

(defn- build-attr
  "A reducer function that compiles interaction data structures
  into apropriate event handler attributes."
  [acc {:keys [trigger] :as interaction}]
  (let [evt (translate-trigger trigger)
        itx (build-interaction interaction)]
    (assoc acc evt (partial default-callback itx))))

(defn build-attrs
  "Compile a sequence of interactions into a hash-map of event-handlers."
  [items]
  (reduce build-attr {} items))




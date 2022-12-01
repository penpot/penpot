;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.features
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.config :as cf]
   [app.main.store :as st]
   [app.util.timers :as tm]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :trace)

(def available-features
  #{:auto-layout :components-v2})

(defn- toggle-feature
  [feature]
  (ptk/reify ::toggle-feature
    ptk/UpdateEvent
    (update [_ state]
      (let [features (or (:features state) #{})]
        (if (contains? features feature)
          (do
            (log/debug :hint "feature disabled" :feature (d/name feature))
            (assoc state :features (disj features feature)))
          (do
            (log/debug :hint "feature enabled" :feature (d/name feature))
            (assoc state :features (conj features feature))))))))

(defn- enable-feature
  [feature]
  (ptk/reify ::enable-feature
    ptk/UpdateEvent
    (update [_ state]
      (let [features (or (:features state) #{})]
        (if (contains? features feature)
          state
          (do
            (log/debug :hint "feature enabled" :feature (d/name feature))
            (assoc state :features (conj features feature))))))))

(defn toggle-feature!
  [feature]
  (assert (contains? available-features feature) "Not supported feature")
  (tm/schedule-on-idle #(st/emit! (toggle-feature feature))))

(defn enable-feature!
  [feature]
  (assert (contains? available-features feature) "Not supported feature")
  (tm/schedule-on-idle #(st/emit! (enable-feature feature))))

(defn active-feature?
  ([feature]
   (active-feature? @st/state feature))
  ([state feature]
   (assert (contains? available-features feature) "Not supported feature")
   (contains? (get state :features) feature)))

(def features
  (l/derived :features st/state))

(defn active-feature
  [feature]
  (l/derived #(contains? % feature) features))

(defn use-feature
  [feature]
  (assert (contains? available-features feature) "Not supported feature")
  (let [active-feature-ref (mf/use-memo (mf/deps feature) #(active-feature feature))
        active-feature? (mf/deref active-feature-ref)]
    active-feature?))

;; Enable all features set on the configuration
(->> @cf/flags
     (map name)
     (keep (fn [flag]
             (when (str/starts-with? flag "frontend-feature-")
               (subs flag 17))))
     (map keyword)
     (run! enable-feature!))

;; Enable the rest of available configuration if we are on development
;; environemnt (aka devenv).
(when *assert*
  ;; By default, all features disabled, except in development
  ;; environment, that are enabled except components-v2
  (->> available-features
       (remove #(= % :components-v2))
       (run! enable-feature!)))

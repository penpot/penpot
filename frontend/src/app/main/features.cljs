;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.features
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.config :as cfg]
   [app.main.store :as st]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(log/set-level! :debug)

(def features-list #{:auto-layout :components-v2})

(defn- toggle-feature
  [feature]
  (ptk/reify ::toggle-feature
    ptk/UpdateEvent
    (update [_ state]
      (log/debug :msg "toggle-feature"
                 :feature (d/name feature)
                 :result (if (not (contains? (:features state) feature))
                           "enabled"
                           "disabled"))
      (-> state
          (update :features
                  (fn [features]
                    (let [features (or features #{})]
                      (if (contains? features feature)
                        (disj features feature)
                        (conj features feature)))))))))

(defn toggle-feature!
  [feature]
  (assert (contains? features-list feature) "Not supported feature")
  (st/emit! (toggle-feature feature)))

(defn active-feature?
  ([feature]
   (active-feature? @st/state feature))
  ([state feature]
   (assert (contains? features-list feature) "Not supported feature")
   (contains? (get state :features) feature)))

(def features
  (l/derived :features st/state))

(defn active-feature
  [feature]
  (l/derived #(contains? % feature) features))

(defn use-feature
  [feature]
  (assert (contains? features-list feature) "Not supported feature")
  (let [active-feature-ref (mf/use-memo (mf/deps feature) #(active-feature feature))
        active-feature? (mf/deref active-feature-ref)]
    active-feature?))

;; Read initial enabled features from config, if set
(if-let [enabled-features @cfg/features]
  (doseq [f enabled-features]
    (toggle-feature! f))
  (when *assert*
    ;; By default, all features disabled, except in development
    ;; environment, that are enabled except components-v2
    (doseq [f features-list]
      (when (not= f :components-v2)
        (toggle-feature! f)))))


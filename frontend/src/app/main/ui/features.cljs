;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.features
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.main.store :as st]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

(log/set-level! :debug)

(def features-list #{:auto-layout})

(defn toggle-feature
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

;; By default the features are active in local environments
(when *assert*
  ;; Activate all features in local environment
  (doseq [f features-list]
    (toggle-feature! f)))

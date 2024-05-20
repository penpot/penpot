;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.test-helpers.ids-map
  (:require
   [app.common.uuid :as uuid]))

;; ---- Helpers to manage ids as known identifiers

(def ^:private idmap (atom {}))
(def ^:private next-uuid-val (atom 1))

(defn reset-idmap! []
  (reset! idmap {})
  (reset! next-uuid-val 1))

(defn set-id!
  [label id]
  (swap! idmap assoc label id))

(defn new-id!
  [label]
  (let [id (uuid/next)]
    (set-id! label id)
    id))

(defn id
  [label]
  (get @idmap label))

(defn test-fixture
  ;; Ensure that each test starts with a clean ids map
  [f]
  (reset-idmap!)
  (f))

(defn label [id]
  (or (->> @idmap
           (filter #(= id (val %)))
           (map key)
           (first))
      (str "<no-label #" (subs (str id) (- (count (str id)) 6)) ">")))

(defn next-uuid []
  (let [current (uuid/custom @next-uuid-val)]
    (swap! next-uuid-val inc)
    current))

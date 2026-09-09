;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.plugins-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.plugins :as ctp]
   [clojure.test :as t]))

(def ^:private valid-entry
  {:plugin-id "plugin-1"
   :name "Test Plugin"
   :description "A test plugin"
   :host "https://example.com"
   :code "(function() {})()"
   :permissions #{"content:read"}})

(t/deftest registry-entry-accepts-valid-plugin
  (t/is (true? (sm/validate ctp/schema:registry-entry valid-entry))))

(t/deftest registry-entry-rejects-oversized-code
  (t/is (false? (sm/validate ctp/schema:registry-entry
                             (assoc valid-entry
                                    :code (apply str (repeat (inc (* 1024 1024)) "x")))))))

(t/deftest registry-entry-rejects-oversized-name
  (t/is (false? (sm/validate ctp/schema:registry-entry
                             (assoc valid-entry :name (apply str (repeat 501 "x")))))))

(t/deftest registry-entry-rejects-oversized-host
  (t/is (false? (sm/validate ctp/schema:registry-entry
                             (assoc valid-entry :host (apply str (repeat 501 "x")))))))

(t/deftest registry-entry-rejects-oversized-description
  (t/is (false? (sm/validate ctp/schema:registry-entry
                             (assoc valid-entry :description (apply str (repeat 4097 "x")))))))

(t/deftest registry-entry-rejects-oversized-icon
  (t/is (false? (sm/validate ctp/schema:registry-entry
                             (assoc valid-entry :icon (apply str (repeat 262145 "x")))))))

(t/deftest registry-entry-accepts-values-at-max
  (t/is (true? (sm/validate ctp/schema:registry-entry
                            (assoc valid-entry
                                   :name (apply str (repeat 500 "x"))
                                   :host (apply str (repeat 500 "x"))
                                   :description (apply str (repeat 4096 "x"))
                                   :icon (apply str (repeat 262144 "x"))
                                   :code (apply str (repeat 1048576 "x")))))))

(defn- make-registry
  [n]
  (let [ids (mapv #(str "plugin-" %) (range n))]
    {:ids ids
     :data (into {} (map (fn [id] [id (assoc valid-entry :plugin-id id)]) ids))}))

(t/deftest plugin-registry-accepts-fifty-plugins
  (t/is (true? (sm/validate ctp/schema:plugin-registry (make-registry 50)))))

(t/deftest plugin-registry-rejects-more-than-fifty-plugins
  (t/is (false? (sm/validate ctp/schema:plugin-registry (make-registry 51)))))

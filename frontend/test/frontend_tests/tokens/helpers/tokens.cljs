;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.helpers.tokens
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]))

(defn get-token [file name]
  (let [data (get-in file [:data])
        tokens-status (cfo/get-tokens-status data)
        tokens-lib    (cfo/get-tokens-lib data)]
    (some-> (cfo/get-tokens-in-active-sets tokens-status tokens-lib)
            (get name))))

(defn apply-token-to-shape
  [file shape-label token-label attributes]
  (let [first-page-id (get-in file [:data :pages 0])
        shape-id (thi/id shape-label)
        token (get-token file token-label)
        applied-attributes (cfo/attributes-map attributes token)]
    (update-in file [:data
                     :pages-index first-page-id
                     :objects shape-id
                     :applied-tokens]
               merge applied-attributes)))

(defn get-tokens-lib [file]
  (get-in file [:data :tokens-lib]))

(defn sample-file-with-tokens
  "Sample file whose theme `:theme`, active with its set `:set`, holds
  `tokens`, attributes of `ctob/make-token` where `:label` names the id.
  `params` go to `ctht/sample-file-with-tokens`."
  [tokens & {:as params}]
  (ctht/sample-file-with-tokens
   (assoc params
          :lib-fn (fn [lib]
                    (reduce (fn [lib {:keys [label] :as attrs}]
                              (ctob/add-token lib (thi/id :set)
                                              (ctob/make-token
                                               (cond-> (dissoc attrs :label)
                                                 (some? label) (assoc :id (thi/new-id! label))))))
                            (-> lib
                                (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set) :name "set"))
                                (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme)
                                                                       :name "theme"
                                                                       :sets #{"set"})))
                            tokens))
          :status-fn #(ctos/set-tokens-status % #{(thi/id :theme)} #{(thi/id :set)}))))

(defn radius-file
  "Sample file with the active token `radius`, a border radius of 50 with
  the id `:radius`."
  []
  (sample-file-with-tokens [{:label :radius :name "radius" :type :border-radius :value 50}]))

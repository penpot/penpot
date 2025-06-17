;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.helpers.tokens
  (:require
   [app.common.files.tokens :as cft]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.tokens-lib :as ctob]))

(defn get-token [file name]
  (some-> (get-in file [:data :tokens-lib])
          (ctob/get-tokens-in-active-sets)
          (get name)))

(defn apply-token-to-shape
  [file shape-label token-label attributes]
  (let [first-page-id (get-in file [:data :pages 0])
        shape-id (thi/id shape-label)
        token (get-token file token-label)
        applied-attributes (cft/attributes-map attributes token)]
    (update-in file [:data
                     :pages-index first-page-id
                     :objects shape-id
                     :applied-tokens]
               merge applied-attributes)))

(defn get-tokens-lib [file]
  (get-in file [:data :tokens-lib]))
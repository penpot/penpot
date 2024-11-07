;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.tokens-list
  (:require
   [app.common.data :as d]
   [app.common.time :as dt]))

(defn tokens-seq
  "Returns a sequence of all tokens within the file data."
  [file-data]
  (vals (:tokens file-data)))

(defn- touch
  "Updates the `modified-at` timestamp of a token."
  [token]
  (assoc token :modified-at (dt/now)))

(defn add-token
  "Adds a new token to the file data, setting its `modified-at` timestamp."
  [file-data token-set-id token]
  (-> file-data
      (update :tokens assoc (:id token) (touch token))
      (d/update-in-when [:token-sets-index token-set-id] #(->
                                                           (update % :tokens conj (:id token))
                                                           (touch)))))

(defn get-token
  "Retrieves a token by its ID from the file data."
  [file-data token-id]
  (get-in file-data [:tokens token-id]))

(defn set-token
  "Sets or updates a token in the file data, updating its `modified-at` timestamp."
  [file-data token]
  (d/assoc-in-when file-data [:tokens (:id token)] (touch token)))

(defn update-token
  "Applies a function to update a token in the file data, then touches it."
  [file-data token-id f & args]
  (d/update-in-when file-data [:tokens token-id] #(-> (apply f % args) (touch))))

(defn delete-token
  "Removes a token from the file data by its ID."
  [file-data token-id]
  (update file-data :tokens dissoc token-id))

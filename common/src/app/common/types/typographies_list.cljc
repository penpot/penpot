;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.typographies-list
  (:require
   [app.common.data :as d]
   [app.common.time :as dt]
   [app.common.types.text :as txt]))

(defn typographies-seq
  [file-data]
  (vals (:typographies file-data)))

(defn- touch
  [typography]
  (assoc typography :modified-at (dt/now)))

(defn add-typography
  [file-data typography]
  (update file-data :typographies assoc (:id typography) (touch typography)))

(defn get-typography
  [file-data typography-id]
  (get-in file-data [:typographies typography-id]))

(defn get-ref-typography
  [library-data typography]
  (when (= (:typography-ref-file typography) (:id library-data))
    (get-typography library-data (:typography-ref-id typography))))

(defn set-typography
  [file-data typography]
  (d/assoc-in-when file-data [:typographies (:id typography)] (touch typography)))

(defn update-typography
  [file-data typography-id f & args]
  (d/update-in-when file-data [:typographies typography-id] #(-> (apply f % args)
                                                                 (touch))))

(defn delete-typography
  [file-data typography-id]
  (update file-data :typographies dissoc typography-id))

(defn used-typographies-changed-since
  "Find all usages of any typography in the library by the given shape, of
   typographies that have ben modified after the date.."
  [shape library since-date]
  (->> shape
       :content
       txt/node-seq
       (keep #(get-ref-typography (:data library) %))
       (remove #(< (:modified-at %) since-date))  ;; Note that :modified-at may be nil
       (map (fn [node] {:shape-id (:id shape)
                        :asset-id (:id node)
                        :asset-type :typography}))))

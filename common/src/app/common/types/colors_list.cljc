;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.colors-list
  (:require
   [app.common.data :as d]
   [app.common.time :as dt]
   [app.common.types.color :as ctc]))

(defn colors-seq
  [file-data]
  (vals (:colors file-data)))

(defn- touch
  [color]
  (assoc color :modified-at (dt/now)))

(defn add-color
  [file-data color]
  (update file-data :colors assoc (:id color) (touch color)))

(defn get-color
  [file-data color-id]
  (get-in file-data [:colors color-id]))

(defn get-ref-color
  [library-data color]
  (when (= (:ref-file color) (:id library-data))
    (get-color library-data (:ref-id color))))

(defn set-color
  [file-data color]
  (d/assoc-in-when file-data [:colors (:id color)] (touch color)))

(defn update-color
  [file-data color-id f & args]
  (d/update-in-when file-data [:colors color-id] #(-> (apply f % args)
                                                      (touch))))

(defn delete-color
  [file-data color-id]
  (update file-data :colors dissoc color-id))

(defn used-colors-changed-since
  "Find all usages of any color in the library by the given shape, of colors
   that have ben modified after the date."
  [shape library since-date]
  (->> (ctc/get-all-colors shape)
       (keep #(get-ref-color (:data library) %))
       (remove #(< (:modified-at %) since-date))  ;; Note that :modified-at may be nil
       (map (fn [color] {:shape-id (:id shape)
                         :asset-id (:id color)
                         :asset-type :color}))))

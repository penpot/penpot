;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.library
  "Exposes file library type data helpers.

  WARNING: It belongs to FILE types in hierarchy of types so: file
  types can import this ns but, but this ns can't import file types."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.time :as dt]
   [app.common.types.shape :as types.shape]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLOR LIBRARY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-colors
  [file-data]
  (:colors file-data))

(defn get-color
  [file-data color-id]
  (dm/get-in file-data [:colors color-id]))

(defn get-ref-color
  [library-data color]
  (when (= (:ref-file color) (:id library-data))
    (get-color library-data (:ref-id color))))

(defn- touch
  [color]
  (assoc color :modified-at (dt/now)))

(defn add-color
  [file-data color]
  (update file-data :colors assoc (:id color) (touch color)))

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
  (->> (types.shape/get-all-colors shape)
       (keep #(get-ref-color (:data library) %))
       (remove #(< (:modified-at %) since-date))  ;; Note that :modified-at may be nil
       (map (fn [color]
              {:shape-id (:id shape)
               :asset-id (:id color)
               :asset-type :color}))))

;: FIXME: revisit the API of this, i think we should pass the whole
;; library data here instead of only colors
(defn sync-colors
  "Look for usage of any color of the given library inside the shape,
  and, in this case, copy the library color into the shape."
  [shape library-id library-colors]
  (letfn [(sync-color [shape position shape-color set-fn _ detach-fn]
            (if (= (:ref-file shape-color) library-id)
              (let [library-color (get library-colors (:ref-id shape-color))]
                (if (some? library-color)
                  (set-fn shape
                          position
                          (:color library-color)
                          (:opacity library-color)
                          (:gradient library-color)
                          (:image library-color))
                  (detach-fn shape position)))
              shape))]

    (types.shape/process-shape-colors shape sync-color)))


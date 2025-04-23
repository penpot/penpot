;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.cleaner
  "A collection of helpers for perform cleaning of artifacts; mainly
  for recently imported shapes."
  (:require
   [app.common.data :as d]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRE DECODE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clean-shape-pre-decode
  "Applies a pre-decode phase migration to the shape"
  [shape]
  (if (= "bool" (:type shape))
    (if-let [content (get shape :bool-content)]
      (-> shape
          (assoc :content content)
          (dissoc :bool-content))
      shape)
    shape))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POST DECODE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fix-shape-shadow-color
  "Some shapes can come with invalid `id` property on shadow colors
  caused by incorrect uuid parsing bug that should be already fixed;
  this function removes the invalid id from the data structure."
  [shape]
  (let [fix-color
        (fn [{:keys [id] :as color}]
          (if (uuid? id)
            color
            (if (and (string? id)
                     (re-matches uuid/regex id))
              (assoc color :id (uuid/uuid id))
              (dissoc color :id))))

        fix-shadow
        (fn [shadow]
          (d/update-when shadow :color fix-color))

        xform
        (map fix-shadow)]

    (d/update-when shape :shadow
                   (fn [shadows]
                     (into [] xform shadows)))))

(defn- fix-root-shape
  "Ensure all root objects are well formed shapes"
  [shape]
  (if (= (:id shape) uuid/zero)
    (-> shape
        (assoc :parent-id uuid/zero)
        (assoc :frame-id uuid/zero)
        ;; We explicitly dissoc them and let the shape-setup
        ;; to regenerate it with valid values.
        (dissoc :selrect)
        (dissoc :points)
        (cts/setup-shape))
    shape))

(defn- fix-legacy-flex-dir
  "This operation is only relevant to old data and it is fixed just
  for convenience."
  [shape]
  (d/update-when shape :layout-flex-dir
                 (fn [dir]
                   (case dir
                     :reverse-row :row-reverse
                     :reverse-column :column-reverse
                     dir))))

(defn clean-shape-post-decode
  "A shape procesor that expected to be executed after schema decoding
  process but before validation."
  [shape]
  (-> shape
      (fix-shape-shadow-color)
      (fix-root-shape)
      (fix-legacy-flex-dir)))

(defn clean-file
  [file & {:as _opts}]
  (let [update-container
        (fn [container]
          (d/update-when container :objects d/update-vals clean-shape-post-decode))]
    (update file :data
            (fn [data]
              (-> data
                  (update :pages-index d/update-vals update-container)
                  (update :components d/update-vals update-container))))))

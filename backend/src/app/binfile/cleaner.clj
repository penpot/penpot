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

(defn- pre-clean-bool-content
  [shape]
  (if-let [content (get shape :bool-content)]
    (-> shape
        (assoc :content content)
        (dissoc :bool-content))
    shape))

(defn- pre-clean-shadow-color
  [shape]
  (d/update-when shape :shadow
                 (fn [shadows]
                   (mapv (fn [shadow]
                           (update shadow :color
                                   (fn [color]
                                     (let [ref-id   (get color :id)
                                           ref-file (get color :file-id)]
                                       (-> (d/without-qualified color)
                                           (select-keys [:opacity :color :gradient :image :ref-id :ref-file])
                                           (cond-> ref-id
                                             (assoc :ref-id ref-id))
                                           (cond-> ref-file
                                             (assoc :ref-file ref-file)))))))
                         shadows))))

(defn clean-shape-pre-decode
  "Applies a pre-decode phase migration to the shape"
  [shape]
  (cond-> shape
    (= "bool" (:type shape))
    (pre-clean-bool-content)

    (contains? shape :shadow)
    (pre-clean-shadow-color)))

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

(defn- fix-container
  [container]
  (-> container
      ;; Remove possible `nil` keys on objects
      (d/update-when :objects dissoc nil)
      (d/update-when :objects d/update-vals clean-shape-post-decode)))

(defn clean-file
  [file & {:as _opts}]
  (update file :data
          (fn [data]
            (-> data
                (d/update-when :pages-index d/update-vals fix-container)
                (d/update-when :components d/update-vals fix-container)
                (d/without-nils)))))

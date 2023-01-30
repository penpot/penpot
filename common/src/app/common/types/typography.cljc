;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.typography
  (:require
    [app.common.text :as txt]
    [clojure.spec.alpha :as s]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::path (s/nilable string?))
(s/def ::font-id string?)
(s/def ::font-family string?)
(s/def ::font-variant-id string?)
(s/def ::font-size string?)
(s/def ::font-weight string?)
(s/def ::font-style string?)
(s/def ::line-height string?)
(s/def ::letter-spacing string?)
(s/def ::text-transform string?)

(s/def ::typography
  (s/keys :req-un [::id
                   ::name
                   ::font-id
                   ::font-family
                   ::font-variant-id
                   ::font-size
                   ::font-weight
                   ::font-style
                   ::line-height
                   ::letter-spacing
                   ::text-transform]
          :opt-un [::path]))

(defn uses-library-typographies?
  "Check if the shape uses any typography in the given library."
  [shape library-id]
  (and (= (:type shape) :text)
       (->> shape
            :content
            ;; Check if any node in the content has a reference for the library
            (txt/node-seq
              #(and (some? (:typography-ref-id %))
                    (= (:typography-ref-file %) library-id))))))

(defn uses-library-typography?
  "Check if the shape uses the given library typography."
  [shape library-id typography-id]
  (and (= (:type shape) :text)
       (->> shape
            :content
            ;; Check if any node in the content has a reference for the library
            (txt/node-seq
              #(and (= (:typography-ref-id %) typography-id)
                    (= (:typography-ref-file %) library-id))))))

(defn remap-typographies
  "Change the shape so that any use of the given typography now points to
  the given library."
  [shape library-id typography]
  (let [remap-typography #(assoc % :typography-ref-file library-id)]

    (update shape :content
            (fn [content]
              (txt/transform-nodes #(= (:typography-ref-id %) (:id typography))
                                   remap-typography
                                   content)))))

(defn remove-external-typographies
  "Change the shape so that any use of an external typography now is removed"
  [shape file-id]
  (let [remove-ref-file #(dissoc % :typography-ref-file :typography-ref-id)]

    (update shape :content
            (fn [content]
              (txt/transform-nodes #(not= (:typography-ref-file %) file-id)
                                   remove-ref-file
                                   content)))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.typography
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.text :as txt]
   [app.common.types.plugins :as ctpg]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:typography
  [:map {:title "Typography"}
   [:id ::sm/uuid]
   [:name :string]
   [:font-id :string]
   [:font-family :string]
   [:font-variant-id :string]
   [:font-size :string]
   [:font-weight :string]
   [:font-style :string]
   [:line-height :string]
   [:letter-spacing :string]
   [:text-transform :string]
   [:modified-at {:optional true} ::sm/inst]
   [:path {:optional true} [:maybe :string]]
   [:plugin-data {:optional true} ::ctpg/plugin-data]])

(sm/register! ::typography schema:typography)

(def check-typography!
  (sm/check-fn ::typography))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-typography
  [{:keys [id name path font-id font-family font-variant-id font-size
           font-weight font-style line-height letter-spacing text-transform]}]
  (-> {:id (or id (uuid/next))
       :name (or name "Typography 1")
       :path path
       :font-id (or font-id "sourcesanspro")
       :font-family (or font-family "sourcesanspro")
       :font-variant-id (or font-variant-id "regular")
       :font-size (or font-size "14")
       :font-weight (or font-weight "480")
       :font-style (or font-style "normal")
       :line-height (or line-height "1.2")
       :letter-spacing (or letter-spacing "0")
       :text-transform (or text-transform "none")}
      (d/without-nils)))

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

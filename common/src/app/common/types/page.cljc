;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page
  (:refer-clojure :exclude [empty?])
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as-alias gpt]
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]
   [app.common.types.grid :as ctg]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:flow
  [:map {:title "Flow"}
   [:id ::sm/uuid]
   [:name :string]
   [:starting-frame ::sm/uuid]])

(def schema:flows
  [:map-of {:gen/max 2} ::sm/uuid schema:flow])

(def schema:guide
  [:map {:title "Guide"}
   [:id ::sm/uuid]
   [:axis [::sm/one-of #{:x :y}]]
   [:position ::sm/safe-number]
   [:frame-id {:optional true} [:maybe ::sm/uuid]]])

(def schema:guides
  [:map-of {:gen/max 2} ::sm/uuid schema:guide])

(def schema:objects
  [:map-of {:gen/max 5} ::sm/uuid ::cts/shape])

(def schema:comment-thread-position
  [:map {:title "CommentThreadPosition"}
   [:frame-id ::sm/uuid]
   [:position ::gpt/point]])

(def schema:page
  [:map {:title "FilePage"}
   [:id ::sm/uuid]
   [:name :string]
   [:index {:optional true} ::sm/int]
   [:objects schema:objects]
   [:default-grids {:optional true} ctg/schema:default-grids]
   [:flows {:optional true} schema:flows]
   [:guides {:optional true} schema:guides]
   [:plugin-data {:optional true} ctpg/schema:plugin-data]
   [:background {:optional true} ctc/schema:hex-color]

   [:comment-thread-positions {:optional true}
    [:map-of ::sm/uuid schema:comment-thread-position]]])

(sm/register! ::objects schema:objects)
(sm/register! ::page schema:page)
(sm/register! ::guide schema:guide)
(sm/register! ::flow schema:flow)

(def valid-guide?
  (sm/lazy-validator schema:guide))

(def check-page
  (sm/check-fn schema:page))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialization

(def root uuid/zero)

(def empty-page-data
  {:objects {root
             (cts/setup-shape {:id root
                               :type :frame
                               :parent-id root
                               :frame-id root
                               :name "Root Frame"})}})

(defn make-empty-page
  [{:keys [id name background]}]
  (-> empty-page-data
      (assoc :id (or id (uuid/next)))
      (assoc :name (d/nilv name "Page 1"))
      (cond-> background
        (assoc :background background))))

(defn get-frame-flow
  [flows frame-id]
  (d/seek #(= (:starting-frame %) frame-id) (vals flows)))

(defn is-empty?
  "Check if page is empty or contains shapes"
  [page]
  (= 1 (count (:objects page))))

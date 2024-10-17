;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.page
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as-alias gpt]
   [app.common.schema :as sm]
   [app.common.types.color :as-alias ctc]
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
   ;; FIXME: remove maybe?
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
   [:objects schema:objects]
   [:default-grids {:optional true} ::ctg/default-grids]
   [:flows {:optional true} schema:flows]
   [:guides {:optional true} schema:guides]
   [:plugin-data {:optional true} ::ctpg/plugin-data]
   [:background {:optional true} ::ctc/rgb-color]

   [:comment-thread-positions {:optional true}
    [:map-of ::sm/uuid schema:comment-thread-position]]

   [:options
    ;; DEPERECATED: remove after 2.3 release
    [:map {:title "PageOptions"}]]])

(sm/register! ::page schema:page)
(sm/register! ::guide schema:guide)
(sm/register! ::flow schema:flow)

(def valid-guide?
  (sm/lazy-validator schema:guide))

;; FIXME: convert to validator
(def check-page!
  (sm/check-fn schema:page))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INIT & HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- Initialization

(def root uuid/zero)

(def empty-page-data
  {:options {}
   :objects {root
             (cts/setup-shape {:id root
                               :type :frame
                               :parent-id root
                               :frame-id root
                               :name "Root Frame"})}})

(defn make-empty-page
  [{:keys [id name]}]
  (-> empty-page-data
      (assoc :id (or id (uuid/next)))
      (assoc :name (or name "Page 1"))))

(defn get-frame-flow
  [flows frame-id]
  (d/seek #(= (:starting-frame %) frame-id) (vals flows)))

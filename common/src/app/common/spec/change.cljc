;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.spec.change
  (:require
   [app.common.spec.color :as color]
   [app.common.spec.file :as file]
   [app.common.spec.page :as page]
   [app.common.spec.shape :as shape]
   [app.common.spec.typography :as typg]
   [clojure.spec.alpha :as s]))

(s/def ::index integer?)
(s/def ::id uuid?)
(s/def ::parent-id uuid?)
(s/def ::frame-id uuid?)
(s/def ::page-id uuid?)
(s/def ::component-id uuid?)
(s/def ::name string?)

(defmulti operation-spec :type)

(s/def :internal.operations.set/attr keyword?)
(s/def :internal.operations.set/val any?)

(s/def :internal.operations.set/touched
  (s/nilable (s/every keyword? :kind set?)))

(s/def :internal.operations.set/remote-synced?
  (s/nilable boolean?))

(defmethod operation-spec :set [_]
  (s/keys :req-un [:internal.operations.set/attr
                   :internal.operations.set/val]))

(defmethod operation-spec :set-touched [_]
  (s/keys :req-un [:internal.operations.set/touched]))

(defmethod operation-spec :set-remote-synced [_]
  (s/keys :req-un [:internal.operations.set/remote-synced?]))

(defmulti change-spec :type)

(s/def :internal.changes.set-option/option any?)
(s/def :internal.changes.set-option/value any?)

(defmethod change-spec :set-option [_]
  (s/keys :req-un [:internal.changes.set-option/option
                   :internal.changes.set-option/value]))

(s/def :internal.changes.add-obj/obj ::shape/shape)

(defn- valid-container-id-frame?
  [o]
  (or (and (contains? o :page-id)
           (not (contains? o :component-id))
           (some? (:frame-id o)))
      (and (contains? o :component-id)
           (not (contains? o :page-id))
           (nil? (:frame-id o)))))

(defn- valid-container-id?
  [o]
  (or (and (contains? o :page-id)
           (not (contains? o :component-id)))
      (and (contains? o :component-id)
           (not (contains? o :page-id)))))

(defmethod change-spec :add-obj [_]
  (s/and (s/keys :req-un [::id :internal.changes.add-obj/obj]
                 :opt-un [::page-id ::component-id ::parent-id ::frame-id])
         valid-container-id-frame?))

(s/def ::operation (s/multi-spec operation-spec :type))
(s/def ::operations (s/coll-of ::operation))

(defmethod change-spec :mod-obj [_]
  (s/and (s/keys :req-un [::id ::operations]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(defmethod change-spec :del-obj [_]
  (s/and (s/keys :req-un [::id]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(defmethod change-spec :reg-objects [_]
  (s/and (s/keys :req-un [::shape/shapes]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(defmethod change-spec :mov-objects [_]
  (s/and (s/keys :req-un [::parent-id ::shape/shapes]
                 :opt-un [::page-id ::component-id ::index])
         valid-container-id?))

(defmethod change-spec :add-page [_]
  (s/or :empty (s/keys :req-un [::id ::name])
        :complete (s/keys :req-un [::page/page])))

(defmethod change-spec :mod-page [_]
  (s/keys :req-un [::id ::name]))

(defmethod change-spec :del-page [_]
  (s/keys :req-un [::id]))

(defmethod change-spec :mov-page [_]
  (s/keys :req-un [::id ::index]))

(defmethod change-spec :add-color [_]
  (s/keys :req-un [::color/color]))

(defmethod change-spec :mod-color [_]
  (s/keys :req-un [::color/color]))

(defmethod change-spec :del-color [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-recent-color/color ::color/recent-color)

(defmethod change-spec :add-recent-color [_]
  (s/keys :req-un [:internal.changes.add-recent-color/color]))

(s/def :internal.changes.media/object ::file/media-object)

(defmethod change-spec :add-media [_]
  (s/keys :req-un [:internal.changes.media/object]))

(s/def :internal.changes.media.mod/object
  (s/and ::file/media-object #(contains? % :id)))

(defmethod change-spec :mod-media [_]
  (s/keys :req-un [:internal.changes.media.mod/object]))

(defmethod change-spec :del-media [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-component/shapes
  (s/coll-of ::shape/shape))

(defmethod change-spec :add-component [_]
  (s/keys :req-un [::id ::name :internal.changes.add-component/shapes]
          :opt-un [::path]))

(defmethod change-spec :mod-component [_]
  (s/keys :req-un [::id]
          :opt-un [::name :internal.changes.add-component/shapes]))

(defmethod change-spec :del-component [_]
  (s/keys :req-un [::id]))

(defmethod change-spec :add-typography [_]
  (s/keys :req-un [::typg/typography]))

(defmethod change-spec :mod-typography [_]
  (s/keys :req-un [::typg/typography]))

(defmethod change-spec :del-typography [_]
  (s/keys :req-un [::typg/id]))

(s/def ::change (s/multi-spec change-spec :type))
(s/def ::changes (s/coll-of ::change))

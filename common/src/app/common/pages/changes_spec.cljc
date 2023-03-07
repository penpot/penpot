;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.pages.changes-spec
  (:require
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.file.media-object :as ctfm]
   [app.common.types.page :as ctp]
   [app.common.types.shape :as cts]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
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

(s/def :internal.changes.add-obj/obj ::cts/shape)

(defn- valid-container-id-frame?
  [o]
  (or (and (contains? o :page-id)
           (not (contains? o :component-id))
           (some? (:frame-id o)))
      (and (contains? o :component-id)
           (not (contains? o :page-id))
           (not= (:frame-id o) uuid/zero))))

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
  (s/and (s/keys :req-un [::cts/shapes]
                 :opt-un [::page-id ::component-id])
         valid-container-id?))

(defmethod change-spec :mov-objects [_]
  (s/and (s/keys :req-un [::parent-id ::cts/shapes]
                 :opt-un [::page-id ::component-id ::index])
         valid-container-id?))

(defmethod change-spec :add-page [_]
  (s/or :empty (s/keys :req-un [::id ::name])
        :complete (s/keys :req-un [::ctp/page])))

(defmethod change-spec :mod-page [_]
  (s/keys :req-un [::id ::name]))

(defmethod change-spec :del-page [_]
  (s/keys :req-un [::id]))

(defmethod change-spec :mov-page [_]
  (s/keys :req-un [::id ::index]))

(defmethod change-spec :add-color [_]
  (s/keys :req-un [::ctc/color]))

(defmethod change-spec :mod-color [_]
  (s/keys :req-un [::ctc/color]))

(defmethod change-spec :del-color [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-recent-color/color ::ctc/recent-color)

(defmethod change-spec :add-recent-color [_]
  (s/keys :req-un [:internal.changes.add-recent-color/color]))


(s/def :internal.changes.add-media/object ::ctfm/media-object)
(defmethod change-spec :add-media [_]
  (s/keys :req-un [:internal.changes.add-media/object]))


(s/def :internal.changes.mod-media/width ::us/safe-integer)
(s/def :internal.changes.mod-media/height ::us/safe-integer)
(s/def :internal.changes.mod-media/path (s/nilable string?))
(s/def :internal.changes.mod-media/mtype string?)
(s/def :internal.changes.mod-media/object
  (s/keys :req-un [::id]
          :opt-un [:internal.changes.mod-media/width
                   :internal.changes.mod-media/height
                   :internal.changes.mod-media/path
                   :internal.changes.mod-media/mtype]))

(defmethod change-spec :mod-media [_]
  (s/keys :req-un [:internal.changes.mod-media/object]))

(defmethod change-spec :del-media [_]
  (s/keys :req-un [::id]))

(s/def :internal.changes.add-component/shapes
  (s/coll-of ::cts/shape))

(defmethod change-spec :add-component [_]
  (s/keys :req-un [::id ::name]
          :opt-un [::path :internal.changes.add-component/shapes]))

(defmethod change-spec :mod-component [_]
  (s/keys :req-un [::id]
          :opt-un [::name :internal.changes.add-component/shapes]))

(s/def :internal.changes.del-component/skip-undelete? boolean?)

(defmethod change-spec :del-component [_]
  (s/keys :req-un [::id]
          :opt-un [:internal.changes.del-component/skip-undelete?]))

(defmethod change-spec :restore-component [_]
  (s/keys :req-un [::id]))

(defmethod change-spec :purge-component [_]
  (s/keys :req-un [::id]))

(defmethod change-spec :add-typography [_]
  (s/keys :req-un [::ctt/typography]))

(defmethod change-spec :mod-typography [_]
  (s/keys :req-un [::ctt/typography]))

(defmethod change-spec :del-typography [_]
  (s/keys :req-un [::ctt/id]))

(s/def ::change (s/multi-spec change-spec :type))
(s/def ::changes (s/coll-of ::change))

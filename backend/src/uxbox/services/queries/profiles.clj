;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.profiles
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]
   [uxbox.util.spec :as us]))

;; --- Helpers & Specs

(declare decode-profile-row)
(declare strip-private-attrs)

(s/def ::email ::us/email)
(s/def ::fullname ::us/string)
(s/def ::metadata any?)
(s/def ::old-password ::us/string)
(s/def ::password ::us/string)
(s/def ::path ::us/string)
(s/def ::user ::us/uuid)
(s/def ::username ::us/string)

;; --- Query: Profile (own)

(defn resolve-thumbnail
  [user]
  (let [opts {:src :photo
              :dst :photo
              :size [100 100]
              :quality 90
              :format "jpg"}]
    (-> (px/submit! #(images/populate-thumbnails user opts))
        (su/handle-on-context))))

(defn get-profile
  [conn id]
  (let [sql "select * from users where id=$1 and deleted_at is null"]
    (-> (db/query-one db/pool [sql id])
        (p/then' decode-profile-row))))

(s/def ::profile
  (s/keys :req-un [::user]))

(sq/defquery :profile
  {:doc "Retrieve the user profile."
   :spec ::profile}
  [{:keys [user] :as params}]
  (-> (get-profile db/pool user)
      (p/then' strip-private-attrs)))

;; --- Attrs Helpers

(defn decode-profile-row
  [{:keys [metadata] :as row}]
  (when row
    (cond-> row
      metadata (assoc :metadata (blob/decode metadata)))))

(defn strip-private-attrs
  "Only selects a publicy visible user attrs."
  [profile]
  (select-keys profile [:id :username :fullname :metadata
                        :email :created-at :photo]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.queries.profile
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.images :as images]
   [uxbox.services.queries :as sq]
   [uxbox.services.util :as su]
   [uxbox.util.blob :as blob]))

;; --- Helpers & Specs

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

;; (defn resolve-thumbnail
;;   [user]
;;   (let [opts {:src :photo
;;               :dst :photo
;;               :size [100 100]
;;               :quality 90
;;               :format "jpg"}]
;;     (-> (px/submit! #(images/populate-thumbnails user opts))
;;         (su/handle-on-context))))

(defn retrieve-profile
  [conn id]
  (let [sql "select * from users where id=$1 and deleted_at is null"]
    (db/query-one db/pool [sql id])))

(s/def ::profile
  (s/keys :req-un [::user]))

(sq/defquery ::profile
  [{:keys [user] :as params}]
  (-> (retrieve-profile db/pool user)
      (p/then' strip-private-attrs)))

;; --- Attrs Helpers

(defn strip-private-attrs
  "Only selects a publicy visible user attrs."
  [profile]
  (select-keys profile [:id :username :fullname :metadata
                        :email :created-at :photo]))

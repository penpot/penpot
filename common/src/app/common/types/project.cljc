
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.project
  (:require
   [app.common.schema :as sm]
   [app.common.time :as cm]))

(def schema:project
  [:map {:title "Profile"}
   [:id ::sm/uuid]
   [:created-at {:optional true} ::cm/inst]
   [:modified-at {:optional true} ::cm/inst]
   [:name :string]
   [:is-default {:optional true} ::sm/boolean]
   [:is-pinned {:optional true} ::sm/boolean]
   [:count {:optional true} ::sm/int]
   [:total-count {:optional true} ::sm/int]
   [:team-id ::sm/uuid]])

(def valid-project?
  (sm/lazy-validator schema:project))

(def check-project
  (sm/check-fn schema:project))

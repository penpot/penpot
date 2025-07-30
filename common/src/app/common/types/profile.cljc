;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.profile
  (:require
   [app.common.schema :as sm]
   [app.common.time :as cm]))

(def schema:profile
  [:map {:title "Profile"}
   [:id ::sm/uuid]
   [:created-at {:optional true} ::cm/inst]
   [:fullname {:optional true} :string]
   [:email {:optional true} :string]
   [:lang {:optional true} :string]
   [:theme {:optional true} :string]
   [:photo-id {:optional true} ::sm/uuid]
   ;; Only present on resolved profile objects, the resolve process
   ;; takes the photo-id or geneates an image from the name
   [:photo-url {:optional true} :string]])

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.shadow
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]))

(def styles #{:drop-shadow :inner-shadow})

(def schema:color
  [:merge {:title "ShadowColor"}
   ctc/schema:color-attrs
   ctc/schema:plain-color])

(def color-attrs
  (sm/keys schema:color))

(def schema:shadow
  [:map {:title "Shadow"}
   [:id [:maybe ::sm/uuid]]
   [:style [::sm/one-of styles]]
   [:offset-x ::sm/safe-number]
   [:offset-y ::sm/safe-number]
   [:blur ::sm/safe-number]
   [:spread ::sm/safe-number]
   [:hidden :boolean]
   [:color schema:color]])

(def check-shadow
  (sm/check-fn schema:shadow))

(def valid-shadow?
  (sm/validator schema:shadow))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.shadow
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]
   [app.common.types.shape.shadow.color :as-alias shadow-color]))

(def styles #{:drop-shadow :inner-shadow})

(sm/def! ::shadow
  [:map {:title "Shadow"}
   [:id [:maybe ::sm/uuid]]
   [:style [::sm/one-of styles]]
   [:offset-x ::sm/safe-number]
   [:offset-y ::sm/safe-number]
   [:blur ::sm/safe-number]
   [:spread ::sm/safe-number]
   [:hidden :boolean]
    ;;FIXME: reuse color?
   [:color
    [:map
     [:color {:optional true} :string]
     [:opacity {:optional true} ::sm/safe-number]
     [:gradient {:optional true} [:maybe ::ctc/gradient]]
     [:file-id {:optional true} [:maybe ::sm/uuid]]
     [:id {:optional true} [:maybe ::sm/uuid]]]]])

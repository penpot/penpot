;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.blur
  (:require
   [app.common.schema :as sm]))

(def schema:blur
  [:map {:title "Blur"}
   [:id ::sm/uuid]
   [:type [:= :layer-blur]]
   [:value ::sm/safe-number]
   [:hidden :boolean]])

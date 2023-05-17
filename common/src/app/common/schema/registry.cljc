;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.schema.registry
  (:require
   [malli.core :as m]
   [malli.registry :as mr]
   [malli.util :as mu]))

(defonce registry (atom {}))

(def default-registry
  (mr/composite-registry
   m/default-registry
   (mu/schemas)
   (mr/mutable-registry registry)))


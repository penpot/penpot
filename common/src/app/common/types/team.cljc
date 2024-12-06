;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.team
  (:require
   [app.common.schema :as sm]))

(def valid-roles
  #{:owner :admin :editor :viewer})

(def permissions-for-role
  {:viewer {:can-edit false :is-admin false :is-owner false}
   :editor {:can-edit true :is-admin false :is-owner false}
   :admin  {:can-edit true :is-admin true :is-owner false}
   :owner  {:can-edit true :is-admin true :is-owner true}})

(sm/register! ::role [::sm/one-of valid-roles])

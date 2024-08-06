;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token-theme
  (:require
   [app.common.schema :as sm]))

(sm/register! ::token-theme
  [:map {:title "TokenTheme"}
   [:id ::sm/uuid]
   [:name :string]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::sm/inst]
   [:sets [:set {:gen/max 10 :gen/min 1} ::sm/uuid]]])

(sm/register! ::token-set-group
  [:map {:title "TokenSetGroup"}
   [:id ::sm/uuid]
   [:name :string]
   [:sets [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]])

(sm/register! ::token-set
  [:map {:title "TokenSet"}
   [:id ::sm/uuid]
   [:name :string]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::sm/inst]
   [:tokens [:vector {:gen/max 10 :gen/min 1} ::sm/uuid]]])

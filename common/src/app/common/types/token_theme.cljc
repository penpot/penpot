;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token-theme
  (:require
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]))

(sm/register! ::token-theme
  [:map {:title "TokenTheme"}
   (dm/legacy [:id {:optional true} [:maybe ::sm/uuid]])
   [:name :string]
   [:group {:optional true} :string]
   [:source? {:optional true} :boolean]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::sm/inst]
   [:sets [:set {:gen/max 10 :gen/min 1} ::sm/uuid]]])

(sm/register! ::token-set
  [:map {:title "TokenSet"}
   [:name :string]
   [:description {:optional true} [:maybe :string]]
   [:modified-at {:optional true} ::sm/inst]
   [:tokens :any]])

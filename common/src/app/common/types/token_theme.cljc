;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.token-theme
  (:require
   [app.common.schema :as sm]))

(sm/register!
 ^{::sm/type ::token-theme}
 [:map {:title "TokenTheme"}
  [:name :string]
  [:group :string]
  [:description [:maybe :string]]
  [:is-source :boolean]
  [:id :string]
  [:modified-at {:optional true} ::sm/inst]
  [:sets :any]])

(sm/register!
 ^{::sm/type ::token-set}
 [:map {:title "TokenSet"}
  [:name :string]
  [:description {:optional true} [:maybe :string]]
  [:modified-at {:optional true} ::sm/inst]
  [:tokens {:optional true} :any]])

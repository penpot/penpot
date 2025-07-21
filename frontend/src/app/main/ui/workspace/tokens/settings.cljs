;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.settings
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.workspace.tokens.settings.menu :refer [token-settings*]]
   [rumext.v2 :as mf]))

(mf/defc token-settings-modal*
  {::mf/register modal/components
   ::mf/register-as :tokens/settings}
  []
  [:> token-settings*])

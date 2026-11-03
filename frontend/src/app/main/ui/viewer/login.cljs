;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.viewer.login
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.auth.login-modal :refer [login-register-modal*]]
   [rumext.v2 :as mf]))

(mf/defc login-register-modal
  {::mf/register modal/components
   ::mf/register-as :login-register}
  [_]
  [:> login-register-modal* {:on-close modal/hide!}])
